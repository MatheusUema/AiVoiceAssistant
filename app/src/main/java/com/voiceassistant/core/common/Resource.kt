package com.voiceassistant.core.common

/**
 * Wrapper genérico para representar o estado de uma operação assíncrona.
 * Usado em toda a camada de domínio e presentation para comunicar
 * Loading / Success / Error sem acoplamento a frameworks específicos.
 */
sealed class Resource<out T> {
    data object Loading : Resource<Nothing>()
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String, val cause: Throwable? = null) : Resource<Nothing>()
}

/** Converte um [Result] da stdlib em [Resource]. */
fun <T> Result<T>.toResource(): Resource<T> =
    fold(
        onSuccess = { Resource.Success(it) },
        onFailure = { Resource.Error(it.message ?: "Erro desconhecido", it) }
    )
