package org.tukutuku.mail

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(TextView(this).apply {
      text = "TukuMail\n\nAndroid client foundation"
      textSize = 24f
      setPadding(48, 96, 48, 48)
    })
  }
}
