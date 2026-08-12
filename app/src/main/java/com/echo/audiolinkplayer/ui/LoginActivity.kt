package com.echo.audiolinkplayer.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.echo.audiolinkplayer.core.CookieStore
import com.echo.audiolinkplayer.core.Settings

/**
 * A plain browser window. The user signs in to a site themselves; when they tap
 * "保存登录" we copy that page's cookies into the file yt-dlp reads. No credentials
 * are ever seen or stored by the app — only the session cookies the site issued.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val urlBar = EditText(this).apply {
            hint = "输入网站地址后回车，例如 https://example.com"
            setSingleLine()
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@LoginActivity).apply {
                text = "打开"
                setOnClickListener { load(urlBar.text.toString()) }
            })
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    url?.let { urlBar.setText(it) }
                }
            }
            webChromeClient = WebChromeClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        Settings.setUserAgent(this, web.settings.userAgentString)

        val save = Button(this).apply {
            text = "保存登录信息并返回"
            setOnClickListener {
                val url = web.url
                if (url.isNullOrEmpty()) {
                    Toast.makeText(context, "先打开并登录一个网站", Toast.LENGTH_SHORT).show()
                } else {
                    CookieManager.getInstance().flush()
                    val n = CookieStore.captureFrom(this@LoginActivity, url)
                    Toast.makeText(context, "已保存 $n 条会话信息", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        root.addView(bar)
        root.addView(save)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        load(intent?.dataString ?: "https://www.google.com")
    }

    private fun load(input: String) {
        val url = input.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
        web.loadUrl(url)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
