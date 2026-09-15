plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
  namespace = "org.tukutuku.mail"
  compileSdk = 36
  defaultConfig { applicationId = "org.tukutuku.mail"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "0.1.0" }
}
