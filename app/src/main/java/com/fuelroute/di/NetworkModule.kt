package com.fuelroute.di

import com.fuelroute.data.places.PlacesService
import com.fuelroute.data.routes.RoutesService
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Adds the Android app identity headers required for an Android-restricted API key to be
 * accepted by Google's web services (Routes, Places). Without them the key yields HTTP 403.
 */
class AndroidAppHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = AndroidAppHeaders.apply(chain.request().newBuilder()).build()
        return chain.proceed(request)
    }
}

object AndroidAppHeaders {
    const val PACKAGE_NAME = "com.fuelroute"

    /** Debug keystore SHA-1 (`.\gradlew.bat signingReport`). */
    const val DEBUG_CERT_SHA1 = "E2:AB:20:64:E9:A8:2F:93:10:3D:77:16:5B:C3:F9:07:0D:E2:2E:60"

    fun apply(builder: Request.Builder): Request.Builder = builder
        .header("X-Android-Package", PACKAGE_NAME)
        .header("X-Android-Cert", DEBUG_CERT_SHA1)
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AndroidAppHeadersInterceptor())
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
