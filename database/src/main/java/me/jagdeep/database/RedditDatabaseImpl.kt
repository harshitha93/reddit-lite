package me.jagdeep.database

import android.text.format.DateUtils
import io.reactivex.Completable
import io.reactivex.Single
import me.jagdeep.data.reddit.model.RedditPostEntity
import me.jagdeep.data.reddit.source.RedditDatabase
import me.jagdeep.database.db.AppDatabase
import me.jagdeep.database.mapper.DatabaseResultMapper
import me.jagdeep.database.model.RedditPost
import me.jagdeep.database.model.Subreddit
import javax.inject.Inject

class RedditDatabaseImpl @Inject constructor(
    private val appDatabase: AppDatabase,
    private val mapper: DatabaseResultMapper
) : RedditDatabase {

    override fun getSubreddit(subreddit: String): Single<List<RedditPostEntity>> {
        return appDatabase.subredditDao().getSubredditByName(subreddit)
            .map { subreddits ->
                if (subreddits.isNotEmpty()) {
                    val cachedSubreddit = subreddits[0]
                    cachedSubreddit.postIds.split(",")
                } else {
                    emptyList()
                }
            }
            .flatMap { pageIds ->
                if (pageIds.isNotEmpty()) {
                    appDatabase.postDao().getPosts(pageIds)
                } else {
                    Single.just(emptyList())
                }
            }
            .map { redditPosts ->
                redditPosts.map { redditPost ->
                    mapper.mapFromDatabase(redditPost)
                }
            }
    }

    override fun isSubredditCacheValid(subreddit: String): Single<Boolean> {
        return appDatabase.subredditDao().getSubredditByName(subreddit)
            .map { subreddits ->
                if (subreddits.isEmpty()) {
                    return@map false
                }

                val isValid =
                    subreddits.first().lastCachedTime + CACHE_VALIDITY > System.currentTimeMillis()

                // if cache is expired, delete all cached entries
                if (!isValid) {
                    appDatabase.subredditDao().deleteSubreddit(subreddit)
                    appDatabase.postDao().deletePosts(subreddit)
                }

                return@map isValid
            }
    }

    override fun cacheSubreddit(subreddit: String, posts: List<RedditPostEntity>) {
        // Store Subreddit
        val cachedSubreddit = Subreddit(
            subreddit = subreddit,
            lastCachedTime = System.currentTimeMillis(),
            postIds = posts.joinToString(",") { it.id }
        )
        appDatabase.subredditDao().insertSubreddit(cachedSubreddit)

        // Store RedditPost
        val cachedSubreddits: List<RedditPost> = posts.map { mapper.mapToDatabase(it) }
        appDatabase.postDao().insert(cachedSubreddits)
    }

    override fun clearSubredditCached(subreddit: String): Completable {
        return Completable.defer {

            // Clear Subreddit
            appDatabase.subredditDao().deleteSubreddit(subreddit)

            // Clear RedditPost
            appDatabase.postDao().deletePosts(subreddit)

            Completable.complete()
        }
    }

    companion object {
        const val CACHE_VALIDITY = DateUtils.MINUTE_IN_MILLIS * 10
    }

}
