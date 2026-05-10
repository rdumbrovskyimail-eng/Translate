package com.translator.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.translator.app.data.settings.AppSettings
import com.translator.app.data.settings.AppSettingsSerializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    fun provideAppSettingsDataStore(
        @ApplicationContext context: Context,
        serializer: AppSettingsSerializer
    ): DataStore<AppSettings> {
        return DataStoreFactory.create(
            serializer = serializer,
            produceFile = { context.dataStoreFile("translator_settings.json") }
        )
    }
}