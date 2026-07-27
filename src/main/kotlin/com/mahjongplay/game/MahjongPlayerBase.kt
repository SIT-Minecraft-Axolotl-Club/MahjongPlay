package com.mahjongplay.game

import com.mahjongplay.model.*
import org.mahjong4j.*
import org.mahjong4j.hands.*
import org.mahjong4j.tile.Tile
import org.mahjong4j.tile.TileType
import org.mahjong4j.yaku.normals.NormalYaku
import org.mahjong4j.yaku.yakuman.Yakuman
import kotlin.math.absoluteValue

fun List<MahjongTile>.toIntArray() = IntArray(Tile.entries.size) { code -> this.count { it.mahjong4jTile.code == code } }

abstract class MahjongPlayerBase {
    abstract val uuid: String
    abstract val displayName: String
    abstract val isRealPlayer: Boolean

    val hands: MutableList<MahjongTile> = mutableListOf()
    var autoArrangeHands: Boolean = true
    val fuuroList: MutableList<Fuuro> = mutableListOf()
    var riichiSengenTile: MahjongTile? = null
    val discardedTiles: MutableList<MahjongTile> = mutableListOf()
    val discardedTilesForDisplay: MutableList<MahjongTile> = mutableListOf()
    val nukiDoraTiles: MutableList<MahjongTile> = mutableListOf()
    var justDrewTile: Boolean = false
    var lastDiscardAllIndex: Int = -1
    var riichiAllIndex: Int = -1

    open var ready: Boolean = false
    var riichi: Boolean = false
    var doubleRiichi: Boolean = false
    
    var riichiStickCount: Int = 0

    var points: Int = 0

    val isMenzenchin: Boolean
        get() = fuuroList.isEmpty() || fuuroList.all { it.mentsu is Kantsu && !it.mentsu.isOpen }

    val isRiichiable: Boolean
        get() = isMenzenchin && !(riichi || doubleRiichi) && tilePairsForRiichi.isNotEmpty() && points >= 1000

    var basicThinkingTime = 0
    var extraThinkingTime = 0

    val numbersOfYaochuuhaiTypes: Int
        get() = hands.map { it.mahjong4jTile }.distinct().count { it.isYaochu }

    fun chii(
        claimedTile: MahjongTile,
        tilePair: Pair<MahjongTile, MahjongTile>,
        target: MahjongPlayerBase,
        onChii: (MahjongPlayerBase) -> Unit = {},
    ) {
        onChii.invoke(this)
        val tileMj4jCodePair: Pair<Tile, Tile> = tilePair.first.mahjong4jTile to tilePair.second.mahjong4jTile
        val claimTarget = ClaimTarget.LEFT
        val tileShuntsu = mutableListOf(
            claimedTile,
            hands.find { it.mahjong4jTile == tileMj4jCodePair.first }!!,
            hands.find { it.mahjong4jTile == tileMj4jCodePair.second }!!
        ).also {
            it.sortBy { tile -> tile.mahjong4jTile.code }
        }
        val middleTile = tileShuntsu[1].mahjong4jTile
        val shuntsu = Shuntsu(true, middleTile)
        val fuuro = Fuuro(mentsu = shuntsu, tiles = tileShuntsu, claimTarget = claimTarget, claimTile = claimedTile)
        var skippedClaimed = false
        tileShuntsu.forEach {
            if (!skippedClaimed && it == claimedTile) {
                skippedClaimed = true
            } else {
                hands.remove(it)
            }
        }
        target.discardedTilesForDisplay -= claimedTile
        fuuroList += fuuro
    }

    fun pon(
        claimedTile: MahjongTile,
        claimTarget: ClaimTarget,
        target: MahjongPlayerBase,
        onPon: (MahjongPlayerBase) -> Unit = {},
    ) {
        onPon.invoke(this)
        val kotsu = Kotsu(true, claimedTile.mahjong4jTile)
        val tilesForPon = tilesForPon(claimedTile)
        val fuuro = Fuuro(mentsu = kotsu, tiles = tilesForPon, claimTarget = claimTarget, claimTile = claimedTile)
        var skippedClaimed = false
        tilesForPon.forEach {
            if (!skippedClaimed && it == claimedTile) {
                skippedClaimed = true
            } else {
                hands.remove(it)
            }
        }
        target.discardedTilesForDisplay -= claimedTile
        fuuroList += fuuro
    }

    fun minkan(
        claimedTile: MahjongTile,
        claimTarget: ClaimTarget,
        target: MahjongPlayerBase,
        onMinkan: (MahjongPlayerBase) -> Unit = {},
    ) {
        onMinkan.invoke(this)
        val kantsu = Kantsu(true, claimedTile.mahjong4jTile)
        val tilesForMinkan = tilesForMinkan(claimedTile)
        val fuuro = Fuuro(mentsu = kantsu, tiles = tilesForMinkan, claimTarget = claimTarget, claimTile = claimedTile)
        var skippedClaimed = false
        tilesForMinkan.forEach {
            if (!skippedClaimed && it == claimedTile) {
                skippedClaimed = true
            } else {
                hands.remove(it)
            }
        }
        target.discardedTilesForDisplay -= claimedTile
        fuuroList += fuuro
    }

    fun ankan(tile: MahjongTile, onAnkan: (MahjongPlayerBase) -> Unit = {}) {
        onAnkan.invoke(this)
        val kantsu = Kantsu(false, tile.mahjong4jTile)
        val tilesForAnkan = tilesForAnkan(tile)
        val fuuro = Fuuro(
            mentsu = kantsu,
            tiles = tilesForAnkan,
            claimTarget = ClaimTarget.SELF,
            claimTile = tile
        )
        tilesForAnkan.forEach { hands.remove(it) }
        fuuroList += fuuro
    }

    fun kakan(tile: MahjongTile, onKakan: (MahjongPlayerBase) -> Unit = {}) {
        onKakan.invoke(this)
        val minKotsu = fuuroList.find { fuuro ->
            fuuro.mentsu is Kotsu && fuuro.tiles.any { it.mahjong4jTile == tile.mahjong4jTile }
        }
        fuuroList -= minKotsu!!
        val kakantsu = Kakantsu(tile.mahjong4jTile)
        val tiles = minKotsu.tiles.toMutableList().also { it += tile }
        val fuuro = Fuuro(
            mentsu = kakantsu,
            tiles = tiles,
            claimTarget = minKotsu.claimTarget,
            claimTile = minKotsu.claimTile
        )
        hands -= tile
        fuuroList += fuuro
    }

    fun canPon(tile: MahjongTile): Boolean = !(riichi || doubleRiichi) && sameTilesInHands(tile).size >= 2

    fun canMinkan(tile: MahjongTile): Boolean = !(riichi || doubleRiichi) && sameTilesInHands(tile).size == 3

    val canKakan: Boolean
        get() = tilesCanKakan.size > 0

    val canAnkan: Boolean
        get() = tilesCanAnkan.isNotEmpty()

    fun canChii(tile: MahjongTile): Boolean = !(riichi || doubleRiichi) && tilePairsForChii(tile).isNotEmpty()

    val canKita: Boolean
        get() = hands.any { it == MahjongTile.NORTH }

    fun declareKita(): MahjongTile {
        val north = hands.first { it == MahjongTile.NORTH }
        hands.remove(north)
        nukiDoraTiles += north
        return north
    }

    open suspend fun askToKita(): Boolean = true

    private fun tilesForPon(tile: MahjongTile): List<MahjongTile> =
        sameTilesInHands(tile).apply {
            if (size > 2) {
                this -= first { !it.isRed }
                sortBy { it.isRed }
            }
            this += tile
        }

    private fun tilesForMinkan(tile: MahjongTile): List<MahjongTile> = sameTilesInHands(tile).also { it += tile }

    private fun tilesForAnkan(tile: MahjongTile): List<MahjongTile> = sameTilesInHands(tile)

    val tilesCanAnkan: Set<MahjongTile>
        get() = buildSet {
            hands.distinct().forEach {
                val count = hands.count { t -> t.mahjong4jTile.code == it.mahjong4jTile.code }
                if (count == 4) this += it
            }
            if (!riichi && !doubleRiichi) return@buildSet
            
            forEach {
                val handsCopy = hands.toMutableList()
                val anKanTilesInHands = hands.filter { t -> t.mahjong4jTile == it.mahjong4jTile }.toMutableList()
                handsCopy -= anKanTilesInHands.toSet()
                val fuuroListCopy = fuuroList.toMutableList().apply {
                    this += Fuuro(
                        mentsu = Kantsu(false, it.mahjong4jTile),
                        tiles = anKanTilesInHands,
                        claimTarget = ClaimTarget.SELF,
                        claimTile = it
                    )
                }
                val mentsuList = fuuroListCopy.map { fuuro -> fuuro.mentsu }
                val calculatedMachi = buildList {
                    MahjongTile.entries.filter { mjTile ->
                        mjTile.code == mjTile.mahjong4jTile.code && mjTile.mahjong4jTile != it.mahjong4jTile
                    }.forEach { mjTile ->
                        val mj4jTile = mjTile.mahjong4jTile
                        val nowHands = handsCopy.toIntArray().apply { this[mj4jTile.code]++ }
                        val winnable = tilesWinnable(
                            hands = nowHands,
                            mentsuList = mentsuList,
                            lastTile = mj4jTile
                        )
                        if (winnable) this += mjTile
                    }
                }
                if (calculatedMachi != machiTiles) {
                    this -= it
                } else {
                    val otherTiles = hands.toIntArray()
                    val mentsuList1 = fuuroList.map { fuuro -> fuuro.mentsu }
                    calculatedMachi.forEach { machiTile ->
                        val tile = machiTile.mahjong4jTile
                        val mj4jHands = Hands(otherTiles, tile, mentsuList1)
                        val mentsuCompSet = mj4jHands.mentsuCompSet
                        val shuntsuList = mentsuCompSet.flatMap { mentsuComp -> mentsuComp.shuntsuList }
                        shuntsuList.forEach { shuntsu ->
                            val middleTile = shuntsu.tile
                            val previousTile = MahjongTile.entries[middleTile.code].previousTile.mahjong4jTile
                            val nextTile = MahjongTile.entries[middleTile.code].nextTile.mahjong4jTile
                            val shuntsuTiles = listOf(previousTile, middleTile, nextTile)
                            if (it.mahjong4jTile in shuntsuTiles) this -= it
                        }
                    }
                }
            }
        }

    private val tilesCanKakan: MutableSet<Pair<MahjongTile, ClaimTarget>>
        get() = mutableSetOf<Pair<MahjongTile, ClaimTarget>>().apply {
            fuuroList.filter { it.mentsu is Kotsu }.forEach { fuuro ->
                val tile = hands.find { it.mahjong4jTile.code == fuuro.claimTile.mahjong4jTile.code }
                if (tile != null) this += tile to fuuro.claimTarget
            }
        }

    fun getTilePairsForChii(tile: MahjongTile): List<Pair<MahjongTile, MahjongTile>> = tilePairsForChii(tile)
    fun getTilePairForPon(tile: MahjongTile): Pair<MahjongTile, MahjongTile> = tilePairForPon(tile)

    private fun tilePairsForChii(tile: MahjongTile): List<Pair<MahjongTile, MahjongTile>> {
        val mj4jTile = tile.mahjong4jTile
        if (mj4jTile.number == 0) return emptyList()
        val next = hands.find { it == tile.nextTile }
        val nextNext = hands.find { it == tile.nextTile.nextTile }
        val previous = hands.find { it == tile.previousTile }
        val previousPrevious = hands.find { it == tile.previousTile.previousTile }
        val pairs = mutableListOf<Pair<MahjongTile, MahjongTile>>()
        
        if (mj4jTile.number < 8 && next != null && nextNext != null) pairs += next to nextNext
        if (mj4jTile.number in 2..8 && previous != null && next != null) pairs += previous to next
        if (mj4jTile.number > 2 && previous != null && previousPrevious != null) pairs += previous to previousPrevious

        val sameTypeRedFiveTile = hands.filter { it.isRed && it.mahjong4jTile.type == mj4jTile.type }.getOrNull(0)
        val canChiiWithRedFive = (mj4jTile.number in 3..4 || mj4jTile.number in 6..7) && sameTypeRedFiveTile != null
        if (canChiiWithRedFive) {
            val redFiveTile = sameTypeRedFiveTile!!
            val redFiveTileCode = redFiveTile.mahjong4jTile.code
            val targetCode = mj4jTile.code
            val gap = redFiveTileCode - targetCode
            if (gap.absoluteValue == 1) {
                val firstTile = MahjongTile.entries[minOf(redFiveTileCode, targetCode)].previousTile
                val lastTile = MahjongTile.entries[maxOf(redFiveTileCode, targetCode)].nextTile
                val allTileInHands = hands.any { it == firstTile } && hands.any { it == lastTile }
                if (allTileInHands) {
                    pairs += firstTile to lastTile
                }
            } else {
                val midTileCode = (redFiveTileCode + targetCode) / 2
                val midTile = MahjongTile.entries[midTileCode]
                val midTileInHands = hands.any { it == midTile }
                if (midTileInHands) {
                    pairs += if (gap > 0) {
                        redFiveTile to midTile
                    } else {
                        midTile to redFiveTile
                    }
                }
            }
        }
        return pairs
    }

    private fun tilePairForPon(tile: MahjongTile): Pair<MahjongTile, MahjongTile> {
        val tiles = tilesForPon(tile)
        return tiles[0] to tiles[1]
    }

    private val tilePairsForRiichi
        get() = buildList {
            if (hands.size != 14 - fuuroList.size * 3) return@buildList
            val listToAdd = buildList {
                hands.forEach { tile ->
                    val nowHands = hands.toMutableList().also { it -= tile }
                    val nowMachi = calculateMachi(hands = nowHands)
                    if (nowMachi.isNotEmpty()) {
                        this += tile to nowMachi
                    }
                }
            }
            addAll(listToAdd.distinct())
        }

    private fun sameTilesInHands(tile: MahjongTile): MutableList<MahjongTile> =
        hands.filter { it.mahjong4jTile == tile.mahjong4jTile }.toMutableList()

    val isTenpai: Boolean
        get() = machiTiles.isNotEmpty()

    val machiTiles: List<MahjongTile>
        get() = calculateMachi()

    val previewMachiTiles: List<MahjongTile>
        get() {
            val waitingHandSize = 13 - fuuroList.size * 3
            if (hands.size == waitingHandSize) return calculateMachi()
            if (hands.size != waitingHandSize + 1) return emptyList()
            val allMachi = mutableSetOf<MahjongTile>()
            hands.distinct().forEach { discard ->
                val remaining = hands.toMutableList().also { it.remove(discard) }
                allMachi.addAll(calculateMachi(hands = remaining))
            }
            return allMachi.toList()
        }

    fun machiIfDiscard(discard: MahjongTile): List<MahjongTile> {
        val remaining = hands.toMutableList().also { it.remove(discard) }
        return calculateMachi(hands = remaining)
    }

    private fun calculateMachi(
        hands: List<MahjongTile> = this.hands,
        fuuroList: List<Fuuro> = this.fuuroList,
    ): List<MahjongTile> {
        val waitingHandSize = 13 - fuuroList.size * 3
        if (hands.size > waitingHandSize) return emptyList()

        return MahjongTile.entries.filter { it.code == it.mahjong4jTile.code }.filter {
            val tileInHandsCount = hands.count { t -> t.mahjong4jTile == it.mahjong4jTile }
            val tileInFuuroCount = fuuroList.sumOf { fuuro -> fuuro.tiles.count { t -> t.mahjong4jTile == it.mahjong4jTile } }
            val allTileHere = (tileInHandsCount + tileInFuuroCount) == 4
            if (allTileHere) return@filter false
            val nowHands = hands.toIntArray().apply { this[it.mahjong4jTile.code]++ }
            val mentsuList = fuuroList.map { fuuro -> fuuro.mentsu }
            tilesWinnable(
                hands = nowHands,
                mentsuList = mentsuList,
                lastTile = it.mahjong4jTile,
            )
        }
    }

    fun calculateMachiAndHan(
        hands: List<MahjongTile> = this.hands,
        fuuroList: List<Fuuro> = this.fuuroList,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
    ): Map<MahjongTile, Int> {
        val allMachi = calculateMachi(hands, fuuroList)
        return allMachi.associateWith { machiTile ->
            val yakuSettlement = calculateYakuSettlement(
                winningTile = machiTile,
                isWinningTileInHands = false,
                hands = hands,
                fuuroList = fuuroList,
                rule = rule,
                generalSituation = generalSituation,
                personalSituation = personalSituation,
                doraIndicators = emptyList(),
                uraDoraIndicators = emptyList()
            )
            yakuSettlement.let { if (it.yakuList.isNotEmpty() || it.yakumanList.isNotEmpty()) -1 else it.han }
        }
    }

    fun isFuriten(tile: MahjongTile, discards: List<MahjongTile>): Boolean =
        isFuriten(tile.mahjong4jTile, discards.map { it.mahjong4jTile })

    fun isFuriten(
        tile: Tile, discards: List<Tile>,
        machi: List<Tile> = this.machiTiles.map { it.mahjong4jTile },
    ): Boolean {
        val discardedTilesList = discardedTiles.map { it.mahjong4jTile }
        if (machi.any { it in discardedTilesList }) return true

        // 同巡振听: 从自己最后一次打牌到现在，是否有听牌被打出
        if (lastDiscardAllIndex >= 0 && lastDiscardAllIndex < discards.size) {
            for (index in lastDiscardAllIndex until discards.lastIndex) {
                if (discards[index] in machi) return true
            }
        }

        // 立直振听: 从立直宣言到现在，是否有听牌被打出
        if ((riichi || doubleRiichi) && riichiAllIndex >= 0 && riichiAllIndex < discards.size) {
            for (index in riichiAllIndex until discards.lastIndex) {
                if (discards[index] in machi) return true
            }
        }
        return false
    }

    fun isIppatsu(players: List<MahjongPlayerBase>, discards: List<MahjongTile>): Boolean {
        if (!(riichi || doubleRiichi) || riichiSengenTile == null) return false
        if (riichiAllIndex < 0 || riichiAllIndex > discards.lastIndex) return false
        if (discards.lastIndex - riichiAllIndex > players.size) return false
        val someoneCalls = discards.slice(riichiAllIndex..discards.lastIndex).any { tile ->
            players.any { player ->
                player.fuuroList.any { fuuro ->
                    tile in fuuro.tiles
                }
            }
        }
        return !someoneCalls
    }

    fun isKokushimuso(tile: Tile): Boolean {
        val otherTiles = hands.toIntArray()
        val mentsuList = fuuroList.toMentsuList()
        val mj4jHands = Hands(otherTiles, tile, mentsuList)
        return mj4jHands.isKokushimuso
    }

    private fun List<Fuuro>.toMentsuList() = this.map { it.mentsu }

    fun canWin(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        hands: List<MahjongTile> = this.hands,
        fuuroList: List<Fuuro> = this.fuuroList,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
    ): Boolean {
        val yakuSettlement = calculateYakuSettlement(
            winningTile = winningTile,
            isWinningTileInHands = isWinningTileInHands,
            hands = hands,
            fuuroList = fuuroList,
            rule = rule,
            generalSituation = generalSituation,
            personalSituation = personalSituation
        )
        return with(yakuSettlement) { yakumanList.isNotEmpty() || doubleYakumanList.isNotEmpty() || han >= rule.minimumHan.han }
    }

    private fun tilesWinnable(
        hands: IntArray,
        mentsuList: List<Mentsu>,
        lastTile: Tile,
    ): Boolean = Hands(hands, lastTile, mentsuList).canWin

    /**
     * 修正 mahjong4j 0.3.2 已驗證的役種誤判:
     * - 三色同順: 真三色漏判、非三色誤判 (依 comp 重算)
     * - 三色同刻: 與三色同順相同模式, 一併重算
     * - 三暗刻: 榮和補完的刻子應視為明刻
     * - 平和: 邊張待ち (12待3 / 89待7) 被誤判為兩面
     * - 純全/混全: 雀頭未檢查幺九、兩役錯誤疊加
     */
    private fun refineYakuStock(
        yakuStock: MutableList<NormalYaku>,
        comp: MentsuComp,
        personalSituation: PersonalSituation,
        winningTile: Tile,
        handsIntArray: IntArray,
    ) {
        val suits = listOf(TileType.MANZU, TileType.PINZU, TileType.SOHZU)

        val shuntsuNumbers = comp.shuntsuList.groupBy({ it.tile.type }, { it.tile.number })
        val hasSanshokuJun = (2..8).any { n -> suits.all { t -> shuntsuNumbers[t]?.contains(n) == true } }
        if (hasSanshokuJun) {
            if (NormalYaku.SANSHOKUDOHJUN !in yakuStock) yakuStock += NormalYaku.SANSHOKUDOHJUN
        } else yakuStock -= NormalYaku.SANSHOKUDOHJUN

        val kotsuNumbers = comp.kotsuKantsu.filter { it.tile.number > 0 }.groupBy({ it.tile.type }, { it.tile.number })
        val hasSanshokuKo = (1..9).any { n -> suits.all { t -> kotsuNumbers[t]?.contains(n) == true } }
        if (hasSanshokuKo) {
            if (NormalYaku.SANSHOKUDOHKO !in yakuStock) yakuStock += NormalYaku.SANSHOKUDOHKO
        } else yakuStock -= NormalYaku.SANSHOKUDOHKO

        val ronCompletedKotsu = !personalSituation.isTsumo && handsIntArray[winningTile.code] == 3 &&
            comp.kotsuList.any { !it.isOpen && it.tile == winningTile }
        val ankoCount = comp.kotsuKantsu.count { !it.isOpen } - if (ronCompletedKotsu) 1 else 0
        if (ankoCount == 3) {
            if (NormalYaku.SANANKO !in yakuStock) yakuStock += NormalYaku.SANANKO
        } else yakuStock -= NormalYaku.SANANKO

        if (NormalYaku.PINFU in yakuStock) {
            val ryanmen = comp.shuntsuList.any {
                !it.isOpen && it.tile.type == winningTile.type &&
                    ((winningTile.number == it.tile.number - 1 && it.tile.number != 8) ||
                        (winningTile.number == it.tile.number + 1 && it.tile.number != 2))
            }
            if (!ryanmen) yakuStock -= NormalYaku.PINFU
        }

        val honorTypes = setOf(TileType.FONPAI, TileType.SANGEN)
        val jantoTile: Tile? = comp.janto?.tile
        val chantaShape = comp.shuntsuList.isNotEmpty() &&
            comp.shuntsuList.all { it.tile.number == 2 || it.tile.number == 8 } &&
            comp.kotsuKantsu.all { it.tile.isYaochu } &&
            jantoTile != null && jantoTile.isYaochu
        val hasHonor = jantoTile?.type in honorTypes || comp.kotsuKantsu.any { it.tile.type in honorTypes }
        val junchanTruth = chantaShape && !hasHonor
        val chantaTruth = chantaShape && hasHonor
        if (junchanTruth) {
            if (NormalYaku.JUNCHAN !in yakuStock) yakuStock += NormalYaku.JUNCHAN
        } else yakuStock -= NormalYaku.JUNCHAN
        if (chantaTruth) {
            if (NormalYaku.CHANTA !in yakuStock) yakuStock += NormalYaku.CHANTA
        } else yakuStock -= NormalYaku.CHANTA
    }

    private fun calculateYakuSettlement(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        hands: List<MahjongTile>,
        fuuroList: List<Fuuro>,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
        doraIndicators: List<MahjongTile> = listOf(),
        uraDoraIndicators: List<MahjongTile> = listOf(),
    ): YakuSettlement {
        val handsIntArray = hands.toIntArray().also { if (!isWinningTileInHands) it[winningTile.mahjong4jTile.code]++ }
        val mentsuList = fuuroList.toMentsuList()
        val mj4jHands = Hands(handsIntArray, winningTile.mahjong4jTile, mentsuList)
        if (!mj4jHands.canWin) return YakuSettlement.NO_YAKU
        val mj4jPlayer = Player(mj4jHands, generalSituation, personalSituation).apply { calculate() }
        var finalHan = 0
        var finalFu = 0
        var finalRedFiveCount = 0
        var finalNormalYakuList = mutableListOf<NormalYaku>()
        val finalYakumanList = mj4jPlayer.yakumanList.toMutableList()
        val finalDoubleYakumanList = mutableListOf<DoubleYakuman>()
        if (!rule.localYaku && Yakuman.RENHO in finalYakumanList) finalYakumanList -= Yakuman.RENHO
        // 庫未處理「榮和補完的刻子不算暗刻」: 雙碰榮和時四暗刻不成立
        if (Yakuman.SUANKO in finalYakumanList && !personalSituation.isTsumo &&
            handsIntArray[winningTile.mahjong4jTile.code] == 3
        ) finalYakumanList -= Yakuman.SUANKO
        
        if (finalYakumanList.isNotEmpty()) {
            val handsWithoutWinningTile = hands.toMutableList().also { if (isWinningTileInHands) it -= winningTile }
            val machiBeforeWin = calculateMachi(handsWithoutWinningTile, fuuroList)
            when {
                Yakuman.DAISUSHI in finalYakumanList -> {
                    finalYakumanList -= Yakuman.DAISUSHI
                    finalDoubleYakumanList += DoubleYakuman.DAISUSHI
                }
                Yakuman.KOKUSHIMUSO in finalYakumanList && machiBeforeWin.size == 13 -> {
                    finalYakumanList -= Yakuman.KOKUSHIMUSO
                    finalDoubleYakumanList += DoubleYakuman.KOKUSHIMUSO_JUSANMENMACHI
                }
                Yakuman.CHURENPOHTO in finalYakumanList && machiBeforeWin.size == 9 -> {
                    finalYakumanList -= Yakuman.CHURENPOHTO
                    finalDoubleYakumanList += DoubleYakuman.JUNSEI_CHURENPOHTO
                }
                Yakuman.SUANKO in finalYakumanList && machiBeforeWin.size == 1 -> {
                    finalYakumanList -= Yakuman.SUANKO
                    finalDoubleYakumanList += DoubleYakuman.SUANKO_TANKI
                }
            }
        } else {
            var finalComp: MentsuComp = mj4jHands.mentsuCompSet.firstOrNull() ?: throw IllegalStateException("Hands cannot win")
            mj4jHands.mentsuCompSet.forEach { comp ->
                val yakuStock = mutableListOf<NormalYaku>()
                val resolverSet = Mahjong4jYakuConfig.getNormalYakuResolverSet(comp, generalSituation, personalSituation)
                resolverSet.filter { it.isMatch }.forEach { yakuStock += it.normalYaku }
                refineYakuStock(yakuStock, comp, personalSituation, winningTile.mahjong4jTile, handsIntArray)

                if (!rule.openTanyao && mj4jHands.isOpen && NormalYaku.TANYAO in yakuStock) yakuStock -= NormalYaku.TANYAO

                val hanSum = if (mj4jHands.isOpen) yakuStock.sumOf { it.kuisagari } else yakuStock.sumOf { it.han }
                if (hanSum > finalHan) {
                    finalHan = hanSum
                    finalNormalYakuList = yakuStock
                    finalComp = comp
                }
            }
            
            if (finalHan >= rule.minimumHan.han) {
                val handsComp = mj4jHands.handsComp
                val isRiichi = NormalYaku.REACH in finalNormalYakuList
                val doraAmount = generalSituation.dora.sumOf { handsComp[it.code] }
                repeat(doraAmount) {
                    NormalYaku.DORA.apply { finalNormalYakuList += this; finalHan += this.han }
                }
                if (isRiichi) {
                    val uraDoraAmount = generalSituation.uradora.sumOf { handsComp[it.code] }
                    repeat(uraDoraAmount) {
                        NormalYaku.URADORA.apply { finalNormalYakuList += this; finalHan += this.han }
                    }
                }
                if (rule.redFive != MahjongRule.RedFive.NONE) {
                    val handsRedFiveCount = this@MahjongPlayerBase.hands.count { it.isRed }
                    val fuuroListRedFiveCount = fuuroList.sumOf { it.tiles.count { tile -> tile.isRed } }
                    finalRedFiveCount = handsRedFiveCount + fuuroListRedFiveCount
                    finalHan += finalRedFiveCount
                }
                val nukiCount = nukiDoraTiles.size
                finalHan += nukiCount
            }
            
            finalFu = when {
                finalNormalYakuList.size == 0 -> 0
                NormalYaku.PINFU in finalNormalYakuList && NormalYaku.TSUMO in finalNormalYakuList -> 20
                NormalYaku.CHITOITSU in finalNormalYakuList -> 25
                else -> {
                    var tmpFu = 20
                    tmpFu += when {
                        personalSituation.isTsumo -> 2
                        !mj4jHands.isOpen -> 10
                        else -> 0
                    }
                    tmpFu += finalComp.allMentsu.sumOf { it.fu }
                    if (!personalSituation.isTsumo && handsIntArray[winningTile.mahjong4jTile.code] == 3) {
                        finalComp.kotsuList.firstOrNull { !it.isOpen && it.tile == winningTile.mahjong4jTile }
                            ?.let { tmpFu -= it.fu / 2 }
                    }
                    tmpFu += if (finalComp.isKanchan(mj4jHands.last) ||
                        finalComp.isPenchan(mj4jHands.last) ||
                        finalComp.isTanki(mj4jHands.last)
                    ) 2 else 0
                    val jantoTile = finalComp.janto.tile
                    if (jantoTile == generalSituation.bakaze) tmpFu += 2
                    if (jantoTile == personalSituation.jikaze) tmpFu += 2
                    if (jantoTile.type == TileType.SANGEN) tmpFu += 2
                    ((tmpFu + 9) / 10) * 10
                }
            }
        }
        val fuuroListForSettlement = fuuroList.map { fuuro ->
            val isAnkan = fuuro.mentsu is Kantsu && !fuuro.mentsu.isOpen
            isAnkan to fuuro.tiles
        }
        val score = if (finalYakumanList.isNotEmpty() || finalDoubleYakumanList.isNotEmpty()) {
            val yakumanScore = finalYakumanList.size * 32000
            val doubleYakumanScore = finalDoubleYakumanList.size * 64000
            val scoreSum = yakumanScore + doubleYakumanScore
            if (personalSituation.isParent) (scoreSum * 1.5).toInt() else scoreSum
        } else {
            Score.calculateScore(personalSituation.isParent, finalHan, finalFu).ron
        }
        return YakuSettlement(
            displayName = displayName,
            uuid = uuid,
            isRealPlayer = isRealPlayer,
            botCode = if (this is MahjongBot) this.botTileCode else MahjongTile.UNKNOWN.code,
            yakuList = finalNormalYakuList,
            yakumanList = finalYakumanList,
            doubleYakumanList = finalDoubleYakumanList,
            nagashiMangan = false,
            redFiveCount = finalRedFiveCount,
            nukiDoraCount = nukiDoraTiles.size,
            riichi = riichi,
            winningTile = winningTile,
            hands = hands,
            fuuroList = fuuroListForSettlement,
            doraIndicators = doraIndicators,
            uraDoraIndicators = uraDoraIndicators,
            fu = finalFu,
            han = finalHan,
            score = score
        )
    }

    fun calcYakuSettlementForWin(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
        doraIndicators: List<MahjongTile>,
        uraDoraIndicators: List<MahjongTile>,
    ): YakuSettlement = calculateYakuSettlement(
        winningTile = winningTile,
        isWinningTileInHands = isWinningTileInHands,
        hands = this.hands,
        fuuroList = this.fuuroList,
        rule = rule,
        generalSituation = generalSituation,
        personalSituation = personalSituation,
        doraIndicators = doraIndicators,
        uraDoraIndicators = uraDoraIndicators
    )

    open suspend fun askToDiscardTile(
        timeoutTile: MahjongTile,
        cannotDiscardTiles: List<MahjongTile>,
        skippable: Boolean,
    ): MahjongTile =
        hands.findLast { tile -> cannotDiscardTiles.none { it.mahjong4jTile == tile.mahjong4jTile } } ?: timeoutTile

    open suspend fun askToChii(
        tile: MahjongTile,
        tilePairs: List<Pair<MahjongTile, MahjongTile>>,
        target: ClaimTarget,
    ): Pair<MahjongTile, MahjongTile>? = null

    open suspend fun askToPonOrChii(
        tile: MahjongTile,
        tilePairsForChii: List<Pair<MahjongTile, MahjongTile>>,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget,
    ): Pair<MahjongTile, MahjongTile>? = null

    open suspend fun askToPon(
        tile: MahjongTile,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget,
    ): Boolean = true

    open suspend fun askToAnkanOrKakan(
        canAnkanTiles: Set<MahjongTile>,
        canKakanTiles: Set<Pair<MahjongTile, ClaimTarget>>,
        rule: MahjongRule,
    ): MahjongTile? = null

    suspend fun askToAnkanOrKakan(rule: MahjongRule): MahjongTile? =
        askToAnkanOrKakan(
            canAnkanTiles = tilesCanAnkan,
            canKakanTiles = tilesCanKakan,
            rule = rule
        )

    open suspend fun askToMinkanOrPon(
        tile: MahjongTile,
        target: ClaimTarget,
        rule: MahjongRule,
    ): MahjongGameBehavior = MahjongGameBehavior.MINKAN

    open suspend fun askToRiichi(
        tilePairsForRiichi: List<Pair<MahjongTile, List<MahjongTile>>> = this.tilePairsForRiichi,
    ): MahjongTile? = null

    open suspend fun askToTsumo(): Boolean = true

    open suspend fun askToRon(tile: MahjongTile, target: ClaimTarget): Boolean = true

    open suspend fun askToKyuushuKyuuhai(): Boolean = true

    fun drawTile(tile: MahjongTile) {
        hands += tile
    }

    fun riichi(riichiSengenTile: MahjongTile, isFirstRound: Boolean) {
        this.riichiSengenTile = riichiSengenTile
        if (isFirstRound) doubleRiichi = true else riichi = true
    }

    fun discardTile(tile: MahjongTile): MahjongTile? =
        hands.findLast { it == tile }?.also {
            hands -= it
            discardedTiles += it
            discardedTilesForDisplay += it
        }
}
