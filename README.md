#### The purpose 
Jepack Compose enable us to make a handy reusable UI code.
This project is an attempt to make a reusable UI architecture for Android using Jetpack Compose.
Let's try to merge Compose functions and unidirectional-data-flow architecture.
#### The idea
Main idea is to enable each UI component have its own state and logic and the ability to communicate with other components.
Plus, we want to `compose` our UI components in a declarative way. 
#### The architecture
The architecture is based on the unidirectional-data-flow. 
From `UI` layer we send `events` to the `Presenter` layer.
From the `Presenter` layer we send `states` to the `UI` layer.
Each `UI` component can add another `UI` component and their presenters will act as plugins.
#### The example
```kotlin
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
```