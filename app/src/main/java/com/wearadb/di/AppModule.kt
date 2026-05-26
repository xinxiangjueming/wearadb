package com.wearadb.di

import android.content.Context
import com.wearadb.data.repository.AdbRepository
import com.wearadb.data.repository.DeviceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDeviceRepository(@ApplicationContext context: Context): DeviceRepository {
        return DeviceRepository(context)
    }

    @Provides
    @Singleton
    fun provideAdbRepository(
        deviceRepository: DeviceRepository,
        @ApplicationContext context: Context
    ): AdbRepository {
        return AdbRepository(deviceRepository, context)
    }
}
