package com.ceyinfo.cerpcashbook.data.remote

import android.content.Context
import com.ceyinfo.cerpcashbook.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private var retrofit: Retrofit? = null
    private var persistentCookieJar: PersistentCookieJar? = null

    private fun getCookieJar(context: Context): PersistentCookieJar {
        if (persistentCookieJar == null) {
            persistentCookieJar = PersistentCookieJar(context.applicationContext)
        }
        return persistentCookieJar!!
    }

    fun getInstance(context: Context): Retrofit {
        if (retrofit == null) {
            val logging = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG)
                    HttpLoggingInterceptor.Level.BODY
                else
                    HttpLoggingInterceptor.Level.NONE
            }

            val client = OkHttpClient.Builder()
                .cookieJar(getCookieJar(context))
                .addInterceptor(logging)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(BuildConfig.API_BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!
    }

    fun getService(context: Context): ApiService {
        return getInstance(context).create(ApiService::class.java)
    }

    fun clearSession() {
        persistentCookieJar?.clear()
    }
}
