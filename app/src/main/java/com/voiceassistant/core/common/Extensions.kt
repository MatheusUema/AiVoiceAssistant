package com.voiceassistant.core.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Transforma um Flow<T> em Flow<Resource<T>>, emitindo Loading no início
 * e capturando exceções como Resource.Error.
 */
fun <T> Flow<T>.asResource(): Flow<Resource<T>> =
    map<T, Resource<T>> { Resource.Success(it) }
        .onStart { emit(Resource.Loading) }
        .catch { emit(Resource.Error(it.message ?: "Erro desconhecido", it)) }
