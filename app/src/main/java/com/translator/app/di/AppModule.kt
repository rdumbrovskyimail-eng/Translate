package com.translator.app.di

import com.translator.app.data.AndroidAudioEngine
import com.translator.app.data.GeminiLiveClient
import com.translator.app.domain.AudioEngine
import com.translator.app.domain.LiveClient
import com.translator.app.util.AppLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideLiveClient(logger: AppLogger): LiveClient = GeminiLiveClient(logger)

    @Provides
    @Singleton
    fun provideAudioEngine(logger: AppLogger): AudioEngine = AndroidAudioEngine(logger)
}