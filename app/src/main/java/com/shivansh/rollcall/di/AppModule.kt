package com.shivansh.rollcall.di

import com.shivansh.rollcall.domain.model.PipelineConfig
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
    fun pipelineConfig() = PipelineConfig()
}
