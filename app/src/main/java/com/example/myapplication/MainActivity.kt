package com.example.myapplication

import android.os.Bundle
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
import javax.inject.Inject
import javax.inject.Scope
import javax.inject.Singleton
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
		val navigation = app.navigation()
		navigation.navigateToRedAndBlue()
		setContent {
			MyApplicationTheme {
				Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
					Column {
						navigation.current.value?.invoke()
					}
				}
			}
		}
	}
}

interface UiEvent
interface UiState
data class RedAndBlueState(val isRow: Boolean) : UiState
class GoRedClickedEvent : UiEvent
class GoBlueClickedEvent : UiEvent
class GoRedAndBlueClickedEvent : UiEvent
interface Interactor

class RedAndBlueInteractor @Inject constructor() : Interactor {
	fun handle(): Flow<RedAndBlueState> {
		return flowOf(RedAndBlueState(isRow = true))
	}
}

abstract class Presenter {
	suspend fun emulateProgressDelay() {
		for (i in 0..100) {
			emitState(ProgressState(i.toFloat() / 100))
			delay(10.milliseconds)
		}
		emitState(ProgressState(-1f))
	}

	private var eventsJob: Job? = null
	private val plugins = mutableListOf<Presenter>()
	private var parent: Presenter? = null

	fun plugin(presenter: Presenter) {
		if (presenter.parent != null) {
			throw Exception("Presenter already has parent")
		}
		plugins.add(presenter)
		presenter.parent = this
	}

	fun unplug(presenter: Presenter) {
		if (presenter.parent !== this) {
			throw Exception("Presenter is not a plugin of this presenter")
		}
		presenter.parent = null
	}

	fun initialize() {
		plugins.forEach { it.initialize() }
		initializeInner()
	}

	fun destroy() {
		plugins.forEach { unplug(it) }
		plugins.clear()
		plugins.forEach { it.destroy() }
		destroyInner()
	}

	open fun initializeInner() {

	}

	open fun destroyInner() {

	}

	protected abstract suspend fun handleSelf(event: UiEvent)

	private suspend fun handleInner(event: UiEvent, caller: Presenter) {
		if (parent !== caller) parent?.handleInner(event, this)
		plugins.forEach { if (it !== caller) it.handleInner(event, this) }
		handleSelf(event)
	}

	fun emitEvent(event: UiEvent) {
		eventsScope.launch {
			sharedEventsFlow.emit(event)
		}
	}

	fun start() {
		plugins.forEach { it.start() }
		val ths = this
		eventsJob = eventsScope.launch {
			sharedEventsFlow
				.collect { event ->
					handleInner(event, ths)
				}
		}
	}

	private val workers = mutableMapOf<String, Job>()
	fun launchWorker(tag: String, block: suspend CoroutineScope.() -> Unit) {
		workers[tag]?.cancel()
		val workerJob = workersScope.launch {
			Log.d(TAG, "$this: launchWorker: $tag")
			block()
		}
		workers[tag] = workerJob
	}


	fun stop() {
		eventsJob?.cancel()
		plugins.forEach { it.stop() }
	}

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

	suspend fun emitState(state: UiState) {
		sharedStatesFlow.emit(state)
	}

	fun sharedStates(): Flow<UiState> {

		return sharedStatesFlow
	}
}


/**
 * This class provides a framework for creating user interfaces with
 * presenters and subcomponents.
 */
abstract class Ui(val presenter: Presenter, vararg subcomponents: Ui) {
	init {
		subcomponents.forEach {
			presenter.plugin(it.presenter)
		}
	}

	fun initialize() {
		presenter.initialize()
	}

	fun destroy() {
		presenter.destroy()
	}

	fun start() {
		presenter.start()
	}

	fun stop() {
		presenter.stop()
	}

	fun event(event: UiEvent) {
		presenter.emitEvent(event)
	}

	@Composable
	operator fun invoke() {
		RenderSelf(presenter.sharedStates())
	}

	@Composable
	abstract fun RenderSelf(state: Flow<UiState>)
}

class ResetTimerEvent : UiEvent
class RedAndBlueBoxesPresenter @Inject constructor(
	val interactor: RedAndBlueInteractor,
) : Presenter() {
	override fun initializeInner() {
		super.initializeInner()
		launchWorker("interactor") {
			interactor.handle().collect { state ->
				emitState(state)
			}
		}
	}

	override suspend fun handleSelf(event: UiEvent) {
		Log.d(TAG, "handleSelf: $event thread: ${Thread.currentThread().name} this: $this")
	}

}

class RedAndBlueBoxesUI @Inject constructor(
	presenter: RedAndBlueBoxesPresenter,
	val c1: RedBoxWithTimerUpUI,
	val c2: BlueBoxWithTimerDownUI,
	val goRedButtonUI: GoRedButtonUI,
	val goBlueButtonUI: GoBlueButtonUI,
	val resetButtonUI: ResetButtonUI
) : Ui(
	presenter,
	c1, c2,
	goRedButtonUI, goBlueButtonUI, resetButtonUI
) {
	init {
		Log.d(TAG, "constructor RedAndBlueBoxesUI: $this")
	}

	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		val redAndBlueState = state.filterIsInstance<RedAndBlueState>().collectAsState(initial = RedAndBlueState(false))
		val progress = state.filterIsInstance<ProgressState>().collectAsState(initial = ProgressState(-1f))
		Column {
			Row {
				resetButtonUI()
				goRedButtonUI()
				goBlueButtonUI()
			}

			if (redAndBlueState.value.isRow) {
				Row {
					c1()
					c2()
				}
			} else {
				Column {
					c1()
					c2()
				}
			}
			if (progress.value.progress >= 0) {
				LinearProgressIndicator(progress = progress.value.progress)
			}
		}
	}
}

data class RedBoxState(val time: String) : UiState
class RedBoxWithTimerUpPresenter @Inject constructor(
	val timeProvider: LocalTimeProvider,
	val dateFormatter: DateFormatter,
) : Presenter() {
	override suspend fun handleSelf(event: UiEvent) {
		Log.d(TAG, "handleSelf: " + event + " thread: " + Thread.currentThread().name + " this: $this")
		when (event) {
			is ResetTimerEvent -> {
				startTimerWorker()
			}
		}
	}

	override fun initializeInner() {
		super.initializeInner()
		startTimerWorker()
	}

	private fun startTimerWorker() {
		launchWorker("timer") {
			emulateProgressDelay()
			Log.d(TAG, "startTimerWorker: timer started")
			var time = timeProvider.getTime()
			while (true) {
				emitState(RedBoxState(dateFormatter.format(time)))
				time++
				delay(500.milliseconds)
			}
		}
	}
}

@Singleton
class LocalTimeProvider @Inject constructor() {

	fun getTime(): Long {
		return System.currentTimeMillis()
	}

}

@Singleton
class DateFormatter @Inject constructor() {
	val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

	fun format(time: Long): String {
		return dateFormat.format(time)
	}

}

@Singleton
class Navigation @Inject constructor() {
	var current = mutableStateOf<Ui?>(null)

	private fun replace(new: Ui) {
		val old = current.value
		new.initialize()
		new.start()
		current.value = new
		old?.stop()
		old?.destroy()
	}

	fun navigateToRed() {
		replace(UIComponent.create().redBoxWithTimerUpUI())
	}

	fun navigateToBlue() {
		replace(UIComponent.create().blueBoxWithTimerDownUI())
	}

	fun navigateToRedAndBlue() {
		replace(UIComponent.create().redAndBlueBoxesUI())
	}


}

@Component
@Singleton
interface AppDaggerSingletonesComponent {
	fun localTimeProvider(): LocalTimeProvider
	fun dateFormatter(): DateFormatter
	fun navigation(): Navigation

	companion object Holder {
		val instance: AppDaggerSingletonesComponent by lazy {
			DaggerAppDaggerSingletonesComponent.builder().build()
		}
	}
}

@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class UIScope

@Component(dependencies = [AppDaggerSingletonesComponent::class])
@UIScope
interface UIComponent {
	fun redBoxWithTimerUpUI(): RedBoxWithTimerUpUI
	fun blueBoxWithTimerDownUI(): BlueBoxWithTimerDownUI
	fun redAndBlueBoxesUI(): RedAndBlueBoxesUI

	companion object {
		fun create(): UIComponent {
			return DaggerUIComponent.builder()
				.appDaggerSingletonesComponent(AppDaggerSingletonesComponent.instance).build()
		}
	}
}

class RedBoxWithTimerUpUI @Inject constructor(
	presenter: RedBoxWithTimerUpPresenter,
	val resetButtonUI: ResetButtonUI,
	val goBlueButtonUI: GoBlueButtonUI,
	val goRedAndBlueButtonUI: GoRedAndBlueButtonUI,
) : Ui(
	presenter,
	resetButtonUI,
	goBlueButtonUI,
	goRedAndBlueButtonUI
) {

	init {
		Log.d(TAG, "constructor RedBoxWithTimerUpUI: ${System.identityHashCode(this)}")
	}

	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		val redBoxState = state.filterIsInstance<RedBoxState>().collectAsState(initial = RedBoxState(""))
		val progress = state.filterIsInstance<ProgressState>().collectAsState(initial = ProgressState(-1f))
		Box(
			modifier = Modifier
				.width(130.dp)
				.background(color = Color.Red)
		) {
			Column {
				Text(text = redBoxState.value.time)
				if (progress.value.progress >= 0) {
					LinearProgressIndicator(progress = progress.value.progress)
				}
				resetButtonUI()
				goBlueButtonUI()
				goRedAndBlueButtonUI()
			}
		}

	}
}

data class BlueBoxState(val time: String) : UiState
data class ProgressState(val progress: Float) : UiState
class BlueBoxWithTimerDownPresenter @Inject constructor(
	val timeProvider: LocalTimeProvider,
	val dateFormatter: DateFormatter,
) : Presenter() {
	override suspend fun handleSelf(event: UiEvent) {
		Log.d(TAG, "handleSelf: " + event + " thread: " + Thread.currentThread().name + " this: $this")
		when (event) {
			is ResetTimerEvent -> {
				Log.d(TAG, "handleSelf: BlueBox reset timer")
				startTimerWorker()
			}
		}
	}

	override fun initializeInner() {
		super.initializeInner()
		startTimerWorker()
	}

	private fun startTimerWorker() {
		launchWorker("timer") {
			emulateProgressDelay()
			Log.d(TAG, "startTimerWorker: timer started")
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
	override suspend fun handleSelf(event: UiEvent) {
		Log.d(TAG, "handleSelf: " + event + " thread: " + Thread.currentThread().name + " this: $this")
	}
}

class ResetButtonUI @Inject constructor(presenter: ResetButtonPresenter) : Ui(presenter) {
	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		Button(onClick = { event(ResetTimerEvent()) }) {
			Text(text = "Reset")
		}
	}
}


class GoBlueButtonPresenter @Inject constructor(
	val navigation: Navigation
) : Presenter() {
	override suspend fun handleSelf(event: UiEvent) {
		Log.d(TAG, "handleSelf: " + event + " thread: " + Thread.currentThread().name + " this: $this")
		when (event) {
			is GoBlueClickedEvent -> {
				launchWorker("go blue") {
					emulateProgressDelay()
					navigation.navigateToBlue()
				}
			}
		}
	}
}

class GoBlueButtonUI @Inject constructor(presenter: GoBlueButtonPresenter) : Ui(presenter) {
	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		Button(onClick = { event(GoBlueClickedEvent()) }) {
			Text(text = "Go Blue")
		}
	}
}

class GoRedButtonPresenter @Inject constructor(
	val navigation: Navigation
) : Presenter() {
	override suspend fun handleSelf(event: UiEvent) {
		Log.d(TAG, "handleSelf: " + event + " thread: " + Thread.currentThread().name + " this: $this")
		when (event) {
			is GoRedClickedEvent -> {
				launchWorker("go red and blue") {
					emulateProgressDelay()
					navigation.navigateToRed()
				}
			}
		}
	}
}


class GoRedButtonUI @Inject constructor(presenter: GoRedButtonPresenter) : Ui(presenter) {
	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		Button(onClick = { event(GoRedClickedEvent()) }) {
			Text(text = "Go Red")
		}
	}
}

class GoRedAndBlueButtonPresenter @Inject constructor(
	val navigation: Navigation
) : Presenter() {
	override suspend fun handleSelf(event: UiEvent) {
		Log.d(TAG, "handleSelf: " + event + " thread: " + Thread.currentThread().name + " this: $this")
		when (event) {
			is GoRedAndBlueClickedEvent -> {
				launchWorker("go red and blue") {
					emulateProgressDelay()
					navigation.navigateToRedAndBlue()
				}
			}
		}
	}
}

class GoRedAndBlueButtonUI @Inject constructor(presenter: GoRedAndBlueButtonPresenter) : Ui(presenter) {
	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		Button(onClick = { event(GoRedAndBlueClickedEvent()) }) {
			Text(text = "Go Red and Blue")
		}
	}
}

class BlueBoxWithTimerDownUI @Inject constructor(
	presenter: BlueBoxWithTimerDownPresenter,
	val resetButtonUI: ResetButtonUI,
	val goRedButtonUI: GoRedButtonUI,
	val goRedAndBlueButtonUI: GoRedAndBlueButtonUI
) : Ui(
	presenter,
	resetButtonUI,
	goRedButtonUI,
	goRedAndBlueButtonUI
) {
	init {
		Log.d(TAG, "constructor BlueBoxWithTimerDownUI: ${System.identityHashCode(this)}")
	}

	@Composable
	override fun RenderSelf(state: Flow<UiState>) {
		val blueBoxState = state.filterIsInstance<BlueBoxState>().collectAsState(initial = BlueBoxState(""))
		val progress = state.filterIsInstance<ProgressState>().collectAsState(initial = ProgressState(-1f))
		Box(
			modifier = Modifier
				.width(130.dp)
				.background(color = Color.Blue)
		) {
			Column {
				Text(text = blueBoxState.value.time)
				if (progress.value.progress >= 0) {
					LinearProgressIndicator(progress = progress.value.progress)
				}
				resetButtonUI()
				goRedButtonUI()
				goRedAndBlueButtonUI()
			}
		}
	}
}

const val TAG = "Arch test"


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
	MyApplicationTheme {
	}
}