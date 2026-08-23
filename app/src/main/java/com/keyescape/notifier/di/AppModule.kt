package com.keyescape.notifier.di

import androidx.room.Room
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.keyescape.notifier.data.KeyescapeApiService
import com.keyescape.notifier.data.MonitoringDao
import com.keyescape.notifier.data.MonitoringDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Singleton
import android.content.Context

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): MonitoringDatabase = Room.databaseBuilder(context, MonitoringDatabase::class.java, "escape-notifier.db").fallbackToDestructiveMigration().build()
    @Provides fun monitoringDao(database: MonitoringDatabase): MonitoringDao = database.monitoringDao()
    @Provides @Singleton fun api(): KeyescapeApiService {
        val client = OkHttpClient.Builder().addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }).build()
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        return Retrofit.Builder().baseUrl("https://www.keyescape.com/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(KeyescapeApiService::class.java)
    }
}
