import androidx.compose.ui.window.ComposeUIViewController
import today.okio.problem.App
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { App() }
