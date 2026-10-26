package com.redditswipe.data

import android.os.SystemClock
import android.util.Xml
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** A news site's RSS or Atom feed. [fullText] feeds carry whole articles; the rest a headline and summary. */
data class NewsFeed(val name: String, val url: String, val category: NewsCategory, val fullText: Boolean = false)

/**
 * How the News screen groups feeds. The political leanings follow common media-bias ratings (such as
 * AllSides) and are only a rough guide.
 */
enum class NewsCategory(val label: String) {
    Local("Local: Mazatlán"),
    Center("Center"),
    Liberal("Liberal"),
    Conservative("Conservative"),
    World("World"),
    Business("Business"),
    Tech("Tech"),
    Science("Science & health"),
    Sports("Sports"),
}

val NEWS_FEEDS = listOf(
    NewsFeed("Mazatlán Interactivo", "https://mazatlaninteractivo.com.mx/feed/", NewsCategory.Local, fullText = true),
    NewsFeed("Línea Directa", "https://lineadirectaportal.com/feed/", NewsCategory.Local),
    // Headlines about the city from every outlet Google News finds (El Universal, Reforma, Noroeste...).
    NewsFeed(
        "Google News: Mazatlán",
        "https://news.google.com/rss/search?q=Mazatl%C3%A1n&hl=es-419&gl=MX&ceid=MX:es-419",
        NewsCategory.Local,
    ),

    NewsFeed("BBC News", "https://feeds.bbci.co.uk/news/rss.xml", NewsCategory.Center),
    NewsFeed("The Hill", "https://thehill.com/feed/", NewsCategory.Center),
    NewsFeed("Axios", "https://api.axios.com/feed/", NewsCategory.Center, fullText = true),
    NewsFeed("Christian Science Monitor", "https://rss.csmonitor.com/feeds/all", NewsCategory.Center),

    NewsFeed("The Guardian", "https://www.theguardian.com/world/rss", NewsCategory.Liberal),
    NewsFeed("New York Times", "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml", NewsCategory.Liberal),
    NewsFeed("NPR", "https://feeds.npr.org/1001/rss.xml", NewsCategory.Liberal),
    NewsFeed("ProPublica", "https://feeds.propublica.org/propublica/main", NewsCategory.Liberal, fullText = true),
    NewsFeed("Vox", "https://www.vox.com/rss/index.xml", NewsCategory.Liberal, fullText = true),
    NewsFeed("The Atlantic", "https://www.theatlantic.com/feed/all/", NewsCategory.Liberal, fullText = true),
    NewsFeed("Mother Jones", "https://www.motherjones.com/feed/", NewsCategory.Liberal, fullText = true),

    NewsFeed("Fox News", "https://moxie.foxnews.com/google-publisher/latest.xml", NewsCategory.Conservative, fullText = true),
    NewsFeed("National Review", "https://www.nationalreview.com/feed/", NewsCategory.Conservative),
    NewsFeed("New York Post", "https://nypost.com/feed/", NewsCategory.Conservative),
    NewsFeed("Washington Times", "https://www.washingtontimes.com/rss/headlines/news/", NewsCategory.Conservative),
    NewsFeed("The Federalist", "https://thefederalist.com/feed/", NewsCategory.Conservative),

    NewsFeed("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", NewsCategory.World),
    NewsFeed("BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml", NewsCategory.World),
    NewsFeed("DW", "https://rss.dw.com/rdf/rss-en-all", NewsCategory.World),
    NewsFeed("France 24", "https://www.france24.com/en/rss", NewsCategory.World),

    NewsFeed("CNBC", "https://www.cnbc.com/id/100003114/device/rss/rss.html", NewsCategory.Business),
    NewsFeed("MarketWatch", "https://feeds.content.dowjones.io/public/rss/mw_topstories", NewsCategory.Business),
    NewsFeed("WSJ Markets", "https://feeds.a.dj.com/rss/RSSMarketsMain.xml", NewsCategory.Business),
    NewsFeed("Fortune", "https://fortune.com/feed/fortune-feeds/?id=3230629", NewsCategory.Business, fullText = true),

    NewsFeed("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", NewsCategory.Tech),
    NewsFeed("The Verge", "https://www.theverge.com/rss/index.xml", NewsCategory.Tech),
    NewsFeed("Wired", "https://www.wired.com/feed/rss", NewsCategory.Tech),
    NewsFeed("TechCrunch", "https://techcrunch.com/feed/", NewsCategory.Tech),

    NewsFeed("The Conversation", "https://theconversation.com/us/articles.atom", NewsCategory.Science, fullText = true),
    NewsFeed("ScienceDaily", "https://www.sciencedaily.com/rss/all.xml", NewsCategory.Science),
    NewsFeed("NASA", "https://www.nasa.gov/feed/", NewsCategory.Science, fullText = true),
    NewsFeed("STAT", "https://www.statnews.com/feed/", NewsCategory.Science),

    NewsFeed("ESPN", "https://www.espn.com/espn/rss/news", NewsCategory.Sports),
    NewsFeed("BBC Sport", "https://feeds.bbci.co.uk/sport/rss.xml", NewsCategory.Sports),
)

/** Feeds switched on until the user picks their own. */
val DEFAULT_NEWS_FEEDS = listOf("The Conversation", "ProPublica", "BBC News", "The Guardian")

/** News older than this leaves the deck (saved articles stay), so a few days away doesn't mean reading last week's news. */
const val NEWS_MAX_AGE_MS = 48 * 60 * 60 * 1000L

fun newsFeed(name: String) = NEWS_FEEDS.firstOrNull { it.name == name }

/**
 * Reads news feeds straight from the publishers (they're public, so no relay is needed) and turns each
 * article into a text card. Images, captions and "continue reading" links are dropped.
 */
class NewsRepository {

    // When each feed was last downloaded. A feed lists the same articles until it updates, and the
    // full-text ones are large, so refills within REFETCH_MS skip it.
    private val fetchedAt = ConcurrentHashMap<String, Long>()

    suspend fun fetchArticles(feed: NewsFeed): List<RedditPost> = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        if (fetchedAt[feed.name]?.let { now - it < REFETCH_MS } == true) return@withContext emptyList()
        val connection = (URL(feed.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            // Some publishers turn away Java's default user agent.
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) RedditSwipe")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw RedditException("${feed.name} returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { parseFeed(it, feed) }.also { fetchedAt[feed.name] = now }
        } finally {
            connection.disconnect()
        }
    }

    /** Handles RSS 2.0 (`<item>`) and Atom (`<entry>`) alike, keyed by local tag names. */
    private fun parseFeed(input: java.io.InputStream, feed: NewsFeed): List<RedditPost> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, null)
        }
        val articles = mutableListOf<RedditPost>()
        var item: MutableMap<String, String>? = null
        var inAuthor = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when {
                    tag == "item" || tag == "entry" -> item = mutableMapOf()
                    item == null -> {}
                    tag == "author" -> inAuthor = true
                    // Atom puts the article URL in an attribute; skip enclosures and comment links.
                    tag == "link" && parser.getAttributeValue(null, "href") != null -> {
                        val rel = parser.getAttributeValue(null, "rel")
                        if (rel == null || rel == "alternate") item.putIfAbsent("link", parser.getAttributeValue(null, "href"))
                    }
                    tag in FIELDS -> {
                        // nextText fails on embedded XHTML; that field is just left out.
                        val text = runCatching { parser.nextText() }.getOrNull()?.trim()
                        val key = if (tag == "name" && inAuthor) "creator" else tag
                        if (!text.isNullOrEmpty() && (tag != "name" || inAuthor)) item.putIfAbsent(key, text)
                    }
                }
                XmlPullParser.END_TAG -> when (tag) {
                    "author" -> inAuthor = false
                    "item", "entry" -> {
                        item?.let { toArticle(it, feed) }?.let(articles::add)
                        item = null
                    }
                }
            }
        }
        return articles
    }

    private fun toArticle(fields: Map<String, String>, feed: NewsFeed): RedditPost? {
        val link = fields["link"] ?: fields["guid"]?.takeIf { it.startsWith("http") } ?: return null
        val title = fields["title"]?.let { htmlToText(it) }?.takeIf { it.isNotBlank() } ?: return null
        // Prefer the full article, falling back to the summary when that's all there is.
        val body = listOf("encoded", "content", "description", "summary")
            .mapNotNull { fields[it]?.let { htmlToText(it) } }
            .maxByOrNull { it.length }
            .orEmpty()
        return RedditPost(
            id = "news:$link",
            subreddit = feed.name,
            title = title,
            body = body.takeUnless { isJustTheHeadline(it, title) }.orEmpty(),
            author = fields["creator"].orEmpty(),
            score = 0,
            numComments = 0,
            permalink = link,
            isNsfw = false,
            isNews = true,
            publishedAt = parseDate(fields["pubDate"] ?: fields["published"] ?: fields["updated"] ?: fields["date"]),
        )
    }

    /**
     * True when the "summary" only repeats the headline, as Google News does ("Headline - Outlet" over
     * "Headline  Outlet"), so the card shows the headline alone.
     */
    private fun isJustTheHeadline(body: String, title: String): Boolean =
        body == title || (body.length < title.length + 40 && body.startsWith(title.substringBeforeLast(" - ")))

    private fun parseDate(text: String?): Long {
        if (text == null) return 0L
        return runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun htmlToText(html: String): String {
        val stripped = html
            .replace(MEDIA_BLOCKS, "")
            .replace(BOILERPLATE, "")
        return HtmlCompat.fromHtml(stripped, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .replace('￼', ' ')
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(EXTRA_BLANK_LINES, "\n\n")
            .trim()
    }

    private companion object {
        const val REFETCH_MS = 10 * 60 * 1000L

        val FIELDS = setOf("title", "link", "guid", "description", "encoded", "content", "summary",
            "pubDate", "published", "updated", "date", "creator", "name")

        // Pictures, their captions and anything that isn't prose.
        val MEDIA_BLOCKS = Regex(
            "<(figure|figcaption|script|style|iframe|video|audio)\\b.*?</\\1>|<img\\b[^>]*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        // "Continue reading..." links (The Guardian) and "The post ... appeared first on ..." (WordPress).
        val BOILERPLATE = Regex(
            "<a\\b[^>]*>\\s*Continue reading\\.*\\s*</a>|<p>\\s*The post .*?appeared first on.*?</p>|<p>\\s*\\(Image credit:.*?\\)\\s*</p>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        val EXTRA_BLANK_LINES = Regex("\n{3,}")
    }
}
