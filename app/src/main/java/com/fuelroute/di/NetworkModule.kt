package com.fuelroute.di

import com.fuelroute.data.places.PlacesService
import com.fuelroute.data.routes.RoutesService
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

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