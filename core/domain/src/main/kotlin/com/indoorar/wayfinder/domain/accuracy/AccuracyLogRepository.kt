package com.indoorar.wayfinder.domain.accuracy

import kotlinx.coroutines.flow.Flow

interface AccuracyLogRepository {
    fun observeSamples(): Flow<List<AccuracySample>>
    suspend fun add(sample: AccuracySample)
    suspend fun clear()
}
