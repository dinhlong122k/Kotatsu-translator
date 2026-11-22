package org.koitharu.kotatsu.translator.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.translator.domain.model.AiProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores AI provider API keys. Values are encrypted with AES/GCM using a key from the Android Keystore
 * and kept in a dedicated preferences file that is excluded from backups.
 *
 * Neither keys nor ciphertexts are ever logged.
 */
@Singleton
class TranslatorSecrets @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val lock = Any()

	private val prefs: SharedPreferences by lazy {
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	}

	/**
	 * @return the key or null if it is not set or cannot be decrypted (a broken value is removed)
	 */
	fun get(provider: AiProvider): String? = synchronized(lock) {
		val prefKey = prefKey(provider)
		val stored = prefs.getString(prefKey, null) ?: return null
		try {
			decrypt(provider, stored)
		} catch (e: Exception) {
			e.printStackTraceDebug()
			prefs.edit { remove(prefKey) }
			null
		}
	}

	/**
	 * @param key null or blank to remove the stored value
	 * @return false if the key could not be encrypted (nothing is stored in this case)
	 */
	fun set(provider: AiProvider, key: String?): Boolean = synchronized(lock) {
		val prefKey = prefKey(provider)
		val value = key?.trim()?.nullIfEmpty()
		if (value == null) {
			prefs.edit { remove(prefKey) }
			return true
		}
		try {
			val encrypted = encrypt(provider, value)
			prefs.edit { putString(prefKey, encrypted) }
			true
		} catch (e: Exception) {
			e.printStackTraceDebug()
			prefs.edit { remove(prefKey) }
			false
		}
	}

	fun has(provider: AiProvider): Boolean = get(provider) != null

	/**
	 * Removes all stored keys
	 */
	fun clearAll() = synchronized(lock) {
		prefs.edit { clear() }
	}

	private fun prefKey(provider: AiProvider) = "api_key_" + provider.name

	private fun encrypt(provider: AiProvider, value: String): String {
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
		cipher.updateAAD(provider.name.toByteArray(Charsets.UTF_8))
		val iv = cipher.iv
		val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
		return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
	}

	private fun decrypt(provider: AiProvider, stored: String): String? {
		val key = findKey() ?: return null
		val bytes = Base64.decode(stored, Base64.NO_WRAP)
		if (bytes.size <= IV_SIZE) {
			return null
		}
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, bytes, 0, IV_SIZE))
		cipher.updateAAD(provider.name.toByteArray(Charsets.UTF_8))
		val decrypted = cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
		return String(decrypted, Charsets.UTF_8).nullIfEmpty()
	}

	private fun findKey(): SecretKey? {
		val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
		return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
	}

	private fun getOrCreateKey(): SecretKey {
		findKey()?.let { return it }
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
		generator.init(
			KeyGenParameterSpec.Builder(
				KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
			).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build(),
		)
		return generator.generateKey()
	}

	private companion object {

		const val PREFS_NAME = "translator_secrets"
		const val KEYSTORE = "AndroidKeyStore"
		const val KEY_ALIAS = "translator_secrets_key"
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val IV_SIZE = 12
		const val TAG_SIZE_BITS = 128
	}
}
