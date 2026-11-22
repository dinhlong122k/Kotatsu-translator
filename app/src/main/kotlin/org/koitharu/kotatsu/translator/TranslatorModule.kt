package org.koitharu.kotatsu.translator

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.network.CloudFlareInterceptor
import org.koitharu.kotatsu.core.network.CurlLoggingInterceptor
import org.koitharu.kotatsu.core.network.GZipInterceptor
import org.koitharu.kotatsu.core.network.RateLimitInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TranslatorHttpClient

@Module
@InstallIn(SingletonComponent::class)
object TranslatorModule {

	/**
	 * Client for AI providers. Derived from the base client (proxy, DNS, certificates are kept) but without
	 * interceptors that break or leak API requests: [GZipInterceptor] declares gzip body that is not compressed,
	 * [CurlLoggingInterceptor] would write API keys to the log, [CloudFlareInterceptor] and [RateLimitInterceptor]
	 * are for manga sources only. No HTTP cache and no read timeout (streaming responses, timeouts are managed
	 * by the SSE layer).
	 */
	@Provides
	@Singleton
	@TranslatorHttpClient
	fun provideTranslatorHttpClient(
		@BaseHttpClient baseHttpClient: OkHttpClient,
	): OkHttpClient = baseHttpClient.newBuilder().apply {
		interceptors().removeAll {
			it is GZipInterceptor || it is CloudFlareInterceptor || it is RateLimitInterceptor || it is CurlLoggingInterceptor
		}
		cache(null)
		cookieJar(CookieJar.NO_COOKIES)
		readTimeout(0, TimeUnit.MILLISECONDS)
	}.build()
}
