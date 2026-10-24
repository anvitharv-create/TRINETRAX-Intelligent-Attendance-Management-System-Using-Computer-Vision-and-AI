package com.trinetrax.attendance.core

import com.trinetrax.attendance.BuildConfig
import com.trinetrax.attendance.data.api.TrinetraApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Base URL rules:
 *  - Phone over USB with `adb reverse tcp:8000 tcp:8000` -> http://127.0.0.1:8000/ (default)
 *  - Android emulator                                 -> http://10.0.2.2:8000/
 *  - Real device over Wi-Fi                           -> http://<PC-LAN-IP>:8000/
 * Override at build time with -PtrinetraApiUrl=... (see gradle.properties).
 */
object NetworkModule {

    const val BASE_URL: String = BuildConfig.BASE_URL

    private lateinit var sessionManager: SessionManager

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val httpClient: OkHttpClient by lazy {
        val auth = Interceptor { chain ->
            val token = sessionManager.token.value
            val original: Request = chain.request()
            val builder = original.newBuilder()
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }
            chain.proceed(builder.build())
        }
        val logging = HttpLoggingInterceptor().apply {
            // Bodies are logged at BASIC only: images are sent as base64 and
            // must never be dumped into logcat.
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(auth)
            .addInterceptor(logging)
            .build()
    }

    val api: TrinetraApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TrinetraApi::class.java)
    }

    fun init(session: SessionManager) {
        sessionManager = session
    }
}
