package com.example.myapplication

import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.MyApplicationTheme
import dagger.Component
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Scope
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val app = AppDaggerSingletonesComponent.instance
		setContent {
			MyApplicationTheme {
				Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
					Column {
						SideEffect {
							assertMainThread()
						}
						LaunchedEffect(Unit) {
							app.navigation().navigateToRedAndBlue()
						}
						app.navigation().current.value?.invoke()
					}
				}
			}
		}
	}
}

// arch

interface UiEvent
interface UiState
interface Interactor
abstract class Presenter {
	init {
		log("creating a new presenter", this)
	}

	enum class State {
		CREATED,
		INITIALIZED,
		STARTED,
		STOPPED,
		DESTROYED
	}

	private var state = State.CREATED
	private var eventsJob: Job? = null
	private val plugins = mutableListOf<Presenter>()
	private var parent: Presenter? = null
	private val workers = mutableMapOf<String, Job>()
	private val workersSupervisorJob = SupervisorJob()
	private val workersCoroutineContext = Dispatchers.Default + workersSupervisorJob
	private val workersScope = CoroutineScope(workersCoroutineContext)
	private val eventsSupervisorJob = SupervisorJob()
	private val eventsCoroutineContext = Dispatchers.Default + eventsSupervisorJob
	private val eventsScope = CoroutineScope(eventsCoroutineContext)
	private val sharedStatesFlow = MutableSharedFlow<UiState>(
		replay = 1,
		extraBufferCapacity = 10,
		onBufferOverflow = BufferOverflow.SUSPEND
	)
	private val sharedEventsFlow = MutableSharedFlow<UiEvent>(
		replay = 1,
		extraBufferCapacity = 10,
		onBufferOverflow = BufferOverflow.SUSPEND
	)

	private fun expectState(vararg expected: State) {
		if (state !in expected) {
			throw Exception("Presenter is in state $state, but expected ${expected.joinToString()}")
		}
	}

	fun plugin(plugin: Presenter) {
		expectState(State.CREATED)
		plugin.expectState(State.CREATED)
		log(this, plugin)
		if (plugin.parent != null) {
			throw Exception("Presenter already has parent")
		}
		log(this, "adding plugin to", System.identityHashCode(plugins))
		plugins.add(plugin)
		plugin.parent = this
	}

	private fun unplug(plugin: Presenter) {
		expectState(State.STOPPED)
		plugin.expectState(State.DESTROYED)
		log(this, plugin)
		if (plugin.parent !== this) {
			throw Exception("This is unknown plugin. $plugin ${plugin.parent} $this")
		}
		plugin.parent = null
	}

	fun initialize() {
		expectState(State.CREATED)
		log(this)
		plugins.forEach {
			it.initialize()
		}
		initializeInner()
		state = State.INITIALIZED
	}

	fun start() {
		expectState(State.INITIALIZED, State.STOPPED)
		log(this)
		plugins.forEach {
			it.start()
		}
		val ths = this
		eventsJob = eventsScope.launch {
			sharedEventsFlow
				.collect { event ->
					handleInner(event, ths)
				}
		}
		state = State.STARTED
	}

	var tmp: Error? = null
	fun stop() {
		tmp = Error("I am stopping myself: $this , parent=$parent")
		expectState(State.STARTED)
		log(this, plugins)
		eventsJob?.cancel()
		log(this, "stopping plugins", System.identityHashCode(plugins))
		plugins.forEach {
			if (it.state == State.STOPPED) {
				it.tmp?.printStackTrace()
				throw Error("plugin is already stopped, this=$this, plugin=$it, ${plugins.joinToString()}", it.tmp)
			}
			it.stop()
			it.tmp = Error("I am stopping this plugin, this=$this, plugin=$it")
		}
		state = State.STOPPED
	}

	fun destroy() {
		expectState(State.STOPPED)
		log(this, plugins)
		destroyInner()
		plugins.forEach {
			it.destroy()
			unplug(it)
		}
		log(this, "clearing plugins", System.identityHashCode(plugins))
		plugins.clear()
		workersSupervisorJob.cancel()
		eventsSupervisorJob.cancel()
		state = State.DESTROYED
	}

	open fun initializeInner() = Unit
	open fun destroyInner() = Unit

	/**
	 * Priority:
	 * 1. Self
	 * 2. Plugins
	 * 3. Parent
	 *
	 * @return true if event was handled
	 */
	private suspend fun handleInner(event: UiEvent, caller: Presenter): Boolean =
		handleSelf(event) ||
			plugins.any { it !== caller && it.handleInner(event, this) } ||
			parent !== caller && parent?.handleInner(event, this) ?: false


	protected abstract suspend fun handleSelf(event: UiEvent): Boolean
	fun emitEvent(event: UiEvent) {
		eventsScope.launch {
			sharedEventsFlow.emit(event)
		}
	}

	fun launchWorker(tag: String, block: suspend CoroutineScope.() -> Unit) {
		log(this, tag)
		workers[tag]?.cancel()
		val workerJob = workersScope.launch {
			log("$this: launchWorker: $tag")
			block()
		}
		workers[tag] = workerJob
	}

	suspend fun emitState(state: UiState) {
		emitStateInner(state, this)
	}

	/**
	 * Priority:
	 * 1. Self
	 * 2. Plugins
	 * 3. Parent
	 */
	private suspend fun emitStateInner(state: UiState, caller: Presenter) {
		sharedStatesFlow.emit(state)
		plugins.forEach { if (it != caller) it.emitStateInner(state, this) }
		if (parent != caller) parent?.emitStateInner(state, this)
	}

	fun sharedStates(): Flow<UiState> = sharedStatesFlow
	suspend fun emulateProgressDelay(state: String) {
		log(this, state)
		for (i in 0..100) {
			emitState(ProgressState(i.toFloat() / 100, state))
			delay(10.milliseconds)
		}
		emitState(ProgressState(-1f, ""))
	}
}


/**
 * This class provides a framework for creating user interfaces with
 * presenters and subcomponents.
 */
abstract class Ui(private val presenter: Presenter, vararg subcomponents: Ui) {
	init {
		log("creating a new Ui", this)
		subcomponents.forEach {
			presenter.plugin(it.presenter)
		}
	}

	enum class State {
		CREATED, INITIALIZING, INITIALIZED, STARTING, STARTED, STOPPING, STOPPED, DESTROYING, DESTROYED,
	}

	private val state = AtomicReference(State.CREATED)

	private fun expectState(vararg expected: State) {
		if (state.get() !in expected) {
			throw Exception("Ui is in state ${state.get()}, but expected ${expected.joinToString()}")
		}
	}

	fun initialize() {
		expectState(State.CREATED)
		if (!state.compareAndSet(State.CREATED, State.INITIALIZING))
			throw Exception("Ui is in state ${state}, but expected ${State.CREATED}")
		log(this)
		presenter.initialize()
		if (!state.compareAndSet(State.INITIALIZING, State.INITIALIZED))
			throw Exception("Ui is in state ${state}, but expected ${State.INITIALIZING}")
	}


	fun start() {
		expectState(State.INITIALIZED, State.STOPPED)
		state.set(State.STARTING)
		log(this)
		presenter.start()
		if (!state.compareAndSet(State.STARTING, State.STARTED))
			throw Exception("Ui is in state ${state}, but expected ${State.STARTING}")
	}

	fun stop() {
		expectState(State.STARTED)
		if (!state.compareAndSet(State.STARTED, State.STOPPING))
			throw Exception("Ui is in state ${state}, but expected ${State.STARTED}")
		log(this)
		presenter.stop()
		if (!state.compareAndSet(State.STOPPING, State.STOPPED))
			throw Exception("Ui is in state ${state}, but expected ${State.STOPPING}")
	}

	fun destroy() {
		expectState(State.STOPPED)
		if (!state.compareAndSet(State.STOPPED, State.DESTROYING))
			throw Exception("Ui is in state ${state}, but expected ${State.STOPPED}")
		log(this)
		presenter.destroy()
		if (!state.compareAndSet(State.DESTROYING, State.DESTROYED))
			throw Exception("Ui is in state ${state}, but expected ${State.DESTROYING}")
	}

	fun event(event: UiEvent) {
		log(this)
		presenter.emitEvent(event)
	}

	@Composable
	operator fun invoke() {
		RenderSelf(presenter.sharedStates())
	}

	@Composable
	abstract fun RenderSelf(state: Flow<UiState>)
}

// di
@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class UiScope

@Component
@Singleton
interface AppDaggerSingletonesComponent {
	fun localTimeProvider(): LocalTimeProvider
	fun dateFormatter(): DateFormatter
	fun navigation(): Navigation

	companion object Holder {
		val instance: AppDaggerSingletonesComponent by lazy {
			log("creating a new AppDaggerSingletonesComponent")
			DaggerAppDaggerSingletonesComponent.builder().build()
		}
	}
}

@Component(dependencies = [AppDaggerSingletonesComponent::class])
@UiScope
interface UiComponent {
	fun redBoxWithTimerUpUi(): RedBoxWithTimerUpUi
	fun blueBoxWithTimerDownUi(): BlueBoxWithTimerDownUi
	fun redAndBlueBoxesUi(): RedAndBlueBoxesUi

	companion object {
		fun create(): UiComponent {
			log("creating a new UiComponent")
			return DaggerUiComponent.builder()
				.appDaggerSingletonesComponent(AppDaggerSingletonesComponent.instance).build()
		}
	}
}

@Singleton
class Navigation @Inject constructor() {
	init {
		log("init navigation", this)
	}

	var current = mutableStateOf<Ui?>(null)
	private fun replace(new: Ui) {
		Error("new UI $new, thread: ${Thread.currentThread().name} ${Thread.currentThread().id}").printStackTrace()
		new.initialize()
		new.start()
		val old = synchronized(this) {
			val old = current.value
			log("replacing $old with $new")
			current.value = new
			old
		}
		old?.stop()
		old?.destroy()
	}

	fun navigateToRed() {
		replace(UiComponent.create().redBoxWithTimerUpUi())
	}

	fun navigateToBlue() {
		replace(UiComponent.create().blueBoxWithTimerDownUi())
	}

	fun navigateToRedAndBlue() {
		replace(UiComponent.create().redAndBlueBoxesUi())
	}
}

// example usage

@Singleton
class LocalTimeProvider @Inject constructor() {
	init {
		log("init LocalTimeProvider", this)
	}

	fun getTime(): Long {
		return System.currentTimeMillis()
	}
}

@Singleton
class DateFormatter @Inject constructor() {
	init {
		log("init DateFormatter", this)
	}

	private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
	fun format(time: Long): String {
		return dateFormat.format(time)
	}
}

data class RedAndBlueState(val isRow: Boolean) : UiState
class GoRedClickedEvent : UiEvent
class GoBlueClickedEvent : UiEvent
class GoRedAndBlueClickedEvent : UiEvent
data class ProgressState(val progress: Float, val state: String) : UiState

class RedAndBlueInteractor @Inject constructor() : Interactor {
	fun handle(): Flow<RedAndBlueState> {
		return flowOf(RedAndBlueState(isRow = Random.nextBoolean()))
	}
}

class ResetTimerEvent : UiEvent
class RedAndBlueBoxesPresenter @Inject constructor(
	private val interactor: RedAndBlueInteractor,
) : Presenter() {
	override fun initializeInner() {
		super.initializeInner()
		launchWorker("interactor") {
			interactor.handle().collect { state ->
				emitState(state)
			}
		}
	}

	override suspend fun handleSelf(event: UiEvent) = false

}

class RedAndBlueBoxesUi @Inject constructor(
	presenter: RedAndBlueBoxesPresenter,
	val redBoxWithTimerUp: RedBoxWithTimerUpUi,
	val blueBoxWithTimerDown: BlueBoxWithTimerDownUi,
	val goRedButtonUi: GoRedButtonUi,
	val goBlueButtonUi: GoBlueButtonUi,
	val resetButtonUi: ResetButtonUi,
	val progressAndStateUi: ProgressAndStateUi
) : Ui(
	presenter,
	redBoxWithTimerUp, blueBoxWithTimerDown,
	goRedButtonUi, goBlueButtonUi, resetButtonUi
) {
	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		val redAndBlueState = state.filterIsInstance<RedAndBlueState>().collectAsState(initial = RedAndBlueState(false))
		Column {
			Row {
				resetButtonUi()
				goRedButtonUi()
				goBlueButtonUi()
			}

			if (redAndBlueState.value.isRow) {
				Row {
					redBoxWithTimerUp()
					blueBoxWithTimerDown()
				}
			} else {
				Column {
					redBoxWithTimerUp()
					blueBoxWithTimerDown()
				}
			}
			progressAndStateUi()
		}
	}
}

data class RedBoxState(val time: String) : UiState
class RedBoxWithTimerUpPresenter @Inject constructor(
	private val timeProvider: LocalTimeProvider,
	private val dateFormatter: DateFormatter,
) : Presenter() {
	override suspend fun handleSelf(event: UiEvent): Boolean {
		log("handleSelf: " + event + " thread: " + Thread.currentThread().name + " this: $this")
		when (event) {
			is ResetTimerEvent -> {
				startTimerWorker()
			}
		}
		return false
	}

	override fun initializeInner() {
		super.initializeInner()
		startTimerWorker()
	}

	private fun startTimerWorker() {
		launchWorker("timer") {
			emulateProgressDelay("starting timer")
			log("startTimerWorker: timer started")
			var time = timeProvider.getTime()
			while (true) {
				emitState(RedBoxState(dateFormatter.format(time)))
				time++
				delay(500.milliseconds)
			}
		}
	}
}


class RedBoxWithTimerUpUi @Inject constructor(
	presenter: RedBoxWithTimerUpPresenter,
	val resetButtonUi: ResetButtonUi,
	val goBlueButtonUi: GoBlueButtonUi,
	val goRedAndBlueButtonUi: GoRedAndBlueButtonUi,
	val progressAndStateUi: ProgressAndStateUi
) : Ui(
	presenter,
	resetButtonUi,
	goBlueButtonUi,
	goRedAndBlueButtonUi
) {
	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		val redBoxState = state.filterIsInstance<RedBoxState>().collectAsState(initial = RedBoxState(""))
		Box(
			modifier = Modifier
				.width(130.dp)
				.background(color = Color.Red)
		) {
			Column {
				Text(text = redBoxState.value.time)
				progressAndStateUi()
				resetButtonUi()
				goBlueButtonUi()
				goRedAndBlueButtonUi()
			}
		}
	}
}

class ProgressAndStatePresenter @Inject constructor() : Presenter() {
	override suspend fun handleSelf(event: UiEvent) = false
}

class ProgressAndStateUi @Inject constructor(presenter: ProgressAndStatePresenter) : Ui(presenter) {
	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		val progress = state.filterIsInstance<ProgressState>().collectAsState(initial = ProgressState(-1f, ""))

		if (progress.value.progress >= 0) {
			log("ProgressAndStateUi: progress: " + progress.value)
			Column {
				LinearProgressIndicator(progress = progress.value.progress)
				Text(text = progress.value.state)
			}
		}
	}
}

data class BlueBoxState(val time: String) : UiState
class BlueBoxWithTimerDownPresenter @Inject constructor(
	private val timeProvider: LocalTimeProvider,
	private val dateFormatter: DateFormatter,
) : Presenter() {
	override suspend fun handleSelf(event: UiEvent): Boolean {
		log("handleSelf: " + event + " thread: " + Thread.currentThread().name + " this: $this")
		when (event) {
			is ResetTimerEvent -> {
				log("handleSelf: BlueBox reset timer")
				startTimerWorker()
			}
		}
		return false
	}

	override fun initializeInner() {
		super.initializeInner()
		startTimerWorker()
	}

	private fun startTimerWorker() {
		launchWorker("timer") {
			emulateProgressDelay("starting timer")
			log("startTimerWorker: timer started")
			var time = timeProvider.getTime()

			while (true) {
				emitState(BlueBoxState(dateFormatter.format(time)))
				time--
				delay(500.milliseconds)
			}
		}
	}
}

class ResetButtonPresenter @Inject constructor() : Presenter() {
	override suspend fun handleSelf(event: UiEvent) = false
}

class ResetButtonUi @Inject constructor(presenter: ResetButtonPresenter) : Ui(presenter) {
	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		Button(onClick = { event(ResetTimerEvent()) }) {
			Text(text = "Reset")
		}
	}
}


class GoBlueButtonPresenter @Inject constructor(
	private val navigation: Navigation
) : Presenter() {
	override suspend fun handleSelf(event: UiEvent): Boolean {
		log("handleSelf: " + event + " thread: " + Thread.currentThread().name + " this: $this")
		when (event) {
			is GoBlueClickedEvent -> {
				launchWorker("go blue") {
					emulateProgressDelay("going blue")
					navigation.navigateToBlue()
				}
				return true
			}
		}
		return false
	}
}

class GoBlueButtonUi @Inject constructor(presenter: GoBlueButtonPresenter) : Ui(presenter) {
	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		Button(onClick = { event(GoBlueClickedEvent()) }) {
			Text(text = "Go Blue")
		}
	}
}

class GoRedButtonPresenter @Inject constructor(
	private val navigation: Navigation
) : Presenter() {
	override suspend fun handleSelf(event: UiEvent): Boolean {
		log("handleSelf: " + event + " thread: " + Thread.currentThread().name + " this: $this")
		when (event) {
			is GoRedClickedEvent -> {
				launchWorker("go red and blue") {
					emulateProgressDelay("going red and blue")
					navigation.navigateToRed()
				}
				return true
			}
		}
		return false
	}
}


class GoRedButtonUi @Inject constructor(presenter: GoRedButtonPresenter) : Ui(presenter) {
	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		Button(onClick = { event(GoRedClickedEvent()) }) {
			Text(text = "Go Red")
		}
	}
}

class GoRedAndBlueButtonPresenter @Inject constructor(
	private val navigation: Navigation
) : Presenter() {
	override suspend fun handleSelf(event: UiEvent): Boolean {
		log("handleSelf: " + event + " thread: " + Thread.currentThread().name + " this: $this")
		when (event) {
			is GoRedAndBlueClickedEvent -> {
				launchWorker("go red and blue") {
					emulateProgressDelay("going red and blue")
					navigation.navigateToRedAndBlue()
				}
				return true
			}
		}
		return false
	}
}

class GoRedAndBlueButtonUi @Inject constructor(presenter: GoRedAndBlueButtonPresenter) : Ui(presenter) {
	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		Button(onClick = { event(GoRedAndBlueClickedEvent()) }) {
			Text(text = "Go Red and Blue")
		}
	}
}

class BlueBoxWithTimerDownUi @Inject constructor(
	presenter: BlueBoxWithTimerDownPresenter,
	val resetButtonUi: ResetButtonUi,
	val goRedButtonUi: GoRedButtonUi,
	val goRedAndBlueButtonUi: GoRedAndBlueButtonUi,
	val progressAndStateUi: ProgressAndStateUi
) : Ui(
	presenter,
	resetButtonUi,
	goRedButtonUi,
	goRedAndBlueButtonUi
) {
	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		val blueBoxState = state.filterIsInstance<BlueBoxState>().collectAsState(initial = BlueBoxState(""))
		Box(
			modifier = Modifier
				.width(130.dp)
				.background(color = Color.Blue)
		) {
			Column {
				Text(text = blueBoxState.value.time)
				progressAndStateUi()
				resetButtonUi()
				goRedButtonUi()
				goRedAndBlueButtonUi()
			}
		}
	}
}

fun log(vararg msg: Any) {
	val trace = Error().stackTrace
	val caller = trace[1]
	Log.d(
		"Arch test", "${caller.className}.${caller.methodName}(${caller.fileName}:${caller.lineNumber})" +
		" ${Thread.currentThread().name}::: ${msg.joinToString { "$it" }}"
	)
}

fun assertMainThread() {
	if (Looper.getMainLooper().thread != Thread.currentThread()) {
		throw IllegalStateException("Not on main thread")
	}
}

fun assertNotMainThread() {
	if (Looper.getMainLooper().thread == Thread.currentThread()) {
		throw IllegalStateException("On main thread")
	}
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
	MyApplicationTheme {
	}
}