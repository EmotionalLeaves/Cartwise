package com.emotionalleaves.cartwise.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Anything Cartwise has seen: a product (ref = ASIN), a search ("q:..."), or a quiz seed ("seed:..."). */
@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val ref: String,
    val title: String,
    val category: String = "",
    val brand: String = "",
    val price: Double? = null,
    val image: String = "",
    val rating: Double? = null,
    val reviews: Int = 0,
    val seller: Double = 0.5,
    val vector: ByteArray? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "signals", indices = [Index("ref"), Index("type")])
data class SignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ref: String,
    val type: String,
    val weight: Double,
    val ts: Long,
    val note: String = "",
)

@Entity(tableName = "orders", primaryKeys = ["orderId", "asin"])
data class OrderEntity(
    val orderId: String,
    val asin: String,
    val title: String,
    val image: String,
    val price: Double?,
    val date: Long,
    val returned: Boolean,
)

@Entity(tableName = "watch")
data class WatchEntity(
    @PrimaryKey val asin: String,
    val title: String,
    val image: String,
    val price: Double?,
    val prevPrice: Double?,
    val target: Double?,
    val addedAt: Long,
    val checkedAt: Long = 0,
    val notifiedAt: Long = 0,
)

/** kind: brand, seller, item (never show), topic (never show), hidden (swiped away in results). */
@Entity(tableName = "blocks", indices = [Index(value = ["kind", "value"], unique = true)])
data class BlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val value: String,
    val label: String = "",
    val vector: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "clusters")
data class ClusterEntity(
    @PrimaryKey val id: String,
    val autoName: String,
    val customName: String?,
    val muted: Boolean,
    val centroid: ByteArray,
    val weight: Double,
    val itemCount: Int,
    val topBrands: String,
    val topCategories: String,
    val topRoots: String,
    val query: String,
    val medianPrice: Double?,
    val examples: String,
    val updatedAt: Long,
)

fun ClusterEntity.displayName(): String = customName?.takeIf { it.isNotBlank() } ?: autoName

@Entity(tableName = "feed")
data class FeedCardEntity(
    @PrimaryKey val asin: String,
    val shelf: String,
    val shelfLabel: String,
    val position: Int,
    val title: String,
    val image: String,
    val price: Double?,
    val rating: Double?,
    val reviews: Int,
    val brand: String,
    val reason: String,
    val score: Double,
    val fit: Double,
    val wildcard: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "impressions")
data class ImpressionEntity(
    @PrimaryKey val asin: String,
    val count: Int,
    val lastDay: Long,
    val interacted: Boolean,
    val firstSeen: Long,
)

@Dao
abstract class CwDao {
    // Items
    @Upsert abstract suspend fun upsertItem(i: ItemEntity)
    @Query("SELECT * FROM items WHERE ref = :ref") abstract suspend fun item(ref: String): ItemEntity?
    @Query("SELECT * FROM items WHERE ref IN (:refs)") abstract suspend fun items(refs: List<String>): List<ItemEntity>
    @Query("UPDATE items SET vector = :v WHERE ref = :ref") abstract suspend fun setVector(ref: String, v: ByteArray)
    @Query("DELETE FROM items") abstract suspend fun clearItems()

    // Signals
    @Insert abstract suspend fun insertSignal(s: SignalEntity)
    @Insert abstract suspend fun insertSignals(s: List<SignalEntity>)
    @Query("SELECT * FROM signals ORDER BY ts DESC LIMIT :n") abstract fun recentSignals(n: Int): Flow<List<SignalEntity>>
    @Query("SELECT * FROM signals") abstract suspend fun allSignals(): List<SignalEntity>
    @Query("SELECT DISTINCT ref FROM signals WHERE type = :type") abstract suspend fun refsWithType(type: String): List<String>
    @Query("DELETE FROM signals WHERE type IN (:types)") abstract suspend fun deleteSignalTypes(types: List<String>)
    @Query("DELETE FROM signals") abstract suspend fun clearSignals()
    @Query("SELECT COUNT(*) FROM signals") abstract fun signalCount(): Flow<Int>

    // Orders
    @Upsert abstract suspend fun upsertOrders(o: List<OrderEntity>)
    @Query("SELECT * FROM orders ORDER BY date DESC") abstract suspend fun orders(): List<OrderEntity>
    @Query("SELECT * FROM orders ORDER BY date DESC") abstract fun ordersFlow(): Flow<List<OrderEntity>>
    @Query("DELETE FROM orders") abstract suspend fun clearOrders()

    // Watchlist
    @Upsert abstract suspend fun upsertWatch(w: WatchEntity)
    @Query("SELECT * FROM watch ORDER BY addedAt DESC") abstract fun watchFlow(): Flow<List<WatchEntity>>
    @Query("SELECT * FROM watch ORDER BY checkedAt ASC") abstract suspend fun watchByStaleness(): List<WatchEntity>
    @Query("SELECT * FROM watch WHERE asin = :asin") abstract suspend fun watch(asin: String): WatchEntity?
    @Query("DELETE FROM watch WHERE asin = :asin") abstract suspend fun deleteWatch(asin: String)

    // Blocklist
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertBlock(b: BlockEntity)
    @Query("SELECT * FROM blocks ORDER BY createdAt DESC") abstract fun blocksFlow(): Flow<List<BlockEntity>>
    @Query("SELECT * FROM blocks") abstract suspend fun blocks(): List<BlockEntity>
    @Query("DELETE FROM blocks WHERE id = :id") abstract suspend fun deleteBlock(id: Long)
    @Query("DELETE FROM blocks WHERE kind = :kind") abstract suspend fun deleteBlockKind(kind: String)

    // Taste clusters
    @Query("SELECT * FROM clusters ORDER BY weight DESC") abstract fun clustersFlow(): Flow<List<ClusterEntity>>
    @Query("SELECT * FROM clusters ORDER BY weight DESC") abstract suspend fun clusters(): List<ClusterEntity>
    @Query("DELETE FROM clusters") abstract suspend fun clearClusters()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertClusters(c: List<ClusterEntity>)
    @Transaction
    open suspend fun replaceClusters(c: List<ClusterEntity>) {
        clearClusters()
        insertClusters(c)
    }
    @Query("UPDATE clusters SET customName = :name WHERE id = :id") abstract suspend fun renameCluster(id: String, name: String?)
    @Query("UPDATE clusters SET muted = :muted WHERE id = :id") abstract suspend fun muteCluster(id: String, muted: Boolean)
    @Query("DELETE FROM clusters WHERE id = :id") abstract suspend fun deleteCluster(id: String)

    // Feed
    @Query("SELECT * FROM feed ORDER BY position ASC") abstract fun feedFlow(): Flow<List<FeedCardEntity>>
    @Query("DELETE FROM feed") abstract suspend fun clearFeed()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertFeed(c: List<FeedCardEntity>)
    @Transaction
    open suspend fun replaceFeed(c: List<FeedCardEntity>) {
        clearFeed()
        insertFeed(c)
    }
    @Query("DELETE FROM feed WHERE asin = :asin") abstract suspend fun removeFeedCard(asin: String)
    @Query("DELETE FROM feed WHERE lower(brand) = lower(:brand)") abstract suspend fun removeFeedBrand(brand: String)

    // Impressions
    @Query("SELECT * FROM impressions WHERE asin = :asin") abstract suspend fun impression(asin: String): ImpressionEntity?
    @Upsert abstract suspend fun upsertImpression(i: ImpressionEntity)
    @Query("SELECT asin FROM impressions") abstract suspend fun seenAsins(): List<String>
    @Query("SELECT asin FROM impressions WHERE interacted = 0 AND lastDay >= :since") abstract suspend fun seenSince(since: Long): List<String>
    @Query("DELETE FROM impressions") abstract suspend fun clearImpressions()
}

@Database(
    entities = [
        ItemEntity::class, SignalEntity::class, OrderEntity::class, WatchEntity::class,
        BlockEntity::class, ClusterEntity::class, FeedCardEntity::class, ImpressionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class CartwiseDb : RoomDatabase() {
    abstract fun dao(): CwDao
}
