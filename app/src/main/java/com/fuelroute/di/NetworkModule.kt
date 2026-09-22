package com.fuelroute.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.fuelroute.data.places.PlacesService
import com.fuelroute.data.routes.RoutesService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.security.MessageDigest
import javax.inject.Singleton

/**
 * Adds the Android app identity headers required for an Android-restricted API key to be
 * accepted by Google's web services (Routes, Places). Without them the key yields HTTP 403.
 *
 * The signing certificate is resolved from the installed APK at runtime so the header always
 * matches whichever key actually signed the build — both debug and release reuse the release
 * keystore, so a hardcoded debug SHA-1 would be wrong.
 */
class AndroidAppHeadersInterceptor(
    private val packageName: String,
    private val certSha1: String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = AndroidAppHeaders.apply(chain.request().newBuilder(), packageName, certSha1).build()
        return chain.proceed(request)
    }
}

object AndroidAppHeaders {
    fun apply(builder: Request.Builder, packageName: String, certSha1: String?): Request.Builder {
        builder.header("X-Android-Package", packageName)
        if (!certSha1.isNullOrBlank()) {
            builder.header("X-Android-Cert", certSha1)
        }
        return builder
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                AndroidAppHeadersInterceptor(
                    packageName = context.packageName,
                    certSha1 = signingCertSha1(context),
                ),
            )
            .build()

    @Provides
    @Singleton
    fun provideRoutesService(client: OkHttpClient, json: Json): RoutesService =
        provideRetrofit(client, json, "https://routes.googleapis.com/")
            .create(RoutesService::class.java)

    @Provides
    @Singleton
    fun providePlacesService(client: OkHttpClient, json: Json): PlacesService =
        provideRetrofit(client, json, "https://places.googleapis.com/")
            .create(PlacesService::class.java)

    private fun provideRetrofit(client: OkHttpClient, json: Json, baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
}

/**
 * SHA-1 (colon-separated uppercase hex) of the APK's current signing certificate, or null when
 * it cannot be resolved. Returns null rather than guessing so a restricted key fails loudly
 * instead of sending a wrong certificate hash.
 */
private fun signingCertSha1(context: Context): String? = try {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        info.signatures
    }
    val cert = signatures?.firstOrNull()?.toByteArray() ?: return null
    MessageDigest.getInstance("SHA-1").digest(cert)
        .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
} catch (_: Throwable) {
    null
}
