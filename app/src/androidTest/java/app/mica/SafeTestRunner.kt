package app.mica

/** Instrumentation is deliberately restricted to the isolated development installation. */
class SafeTestRunner : androidx.test.runner.AndroidJUnitRunner() {
    override fun onStart() {
        check(targetContext.packageName == "app.mica.debug") {
            "Refusing to run instrumentation against the everyday release installation"
        }
        super.onStart()
    }
}
