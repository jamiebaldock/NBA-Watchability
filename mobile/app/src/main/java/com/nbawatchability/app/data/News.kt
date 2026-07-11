package com.nbawatchability.app.data

import kotlinx.serialization.Serializable

@Serializable
data class NewsArticle(
    val id: Long,
    val headline: String,
    val description: String? = null,
    val image: String? = null,
    val link: String? = null,
    val published: String
)

@Serializable
data class NewsResponse(
    val articles: List<NewsArticle>
)
