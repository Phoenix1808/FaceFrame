package com.example.faceframe.processing

/**
 * Agglomerative clustering with average linkage: start with everything in its
 * own cluster, keep merging the closest pair until nothing is close enough.
 *
 * Not K-Means, because K-Means wants the number of clusters up front and the
 * number of people in a video is exactly what we are trying to find out.
 *
 * Generic because we cluster Tracklets, not individual frames.
 */
object FaceClusterer {

    fun <T> cluster(
        items: List<T>,
        threshold: Float = ProcessingConfig.SIMILARITY_THRESHOLD,
        minClusterSize: Int = 1,
        cannotMerge: (T, T) -> Boolean = { _, _ -> false },
        embeddingOf: (T) -> FloatArray
    ): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val n = items.size
        val embeddings = items.map(embeddingOf)

        val pairSim = Array(n) { FloatArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val s = FaceEmbedder.cosineSimilarity(embeddings[i], embeddings[j])
                pairSim[i][j] = s
                pairSim[j][i] = s
            }
        }

        // Some pairs can never merge no matter what the embeddings say. Two
        // tracklets running at the same instant are two different people, and
        // that fact is a lot more reliable than the model's opinion.
        val blocked = Array(n) { i ->
            BooleanArray(n) { j -> i != j && cannotMerge(items[i], items[j]) }
        }

        val members = MutableList(n) { mutableListOf(it) }
        val alive = BooleanArray(n) { true }

        // simSum[a][b] holds the SUM of the pairwise similarities between the
        // two clusters, not the average, because a merge is then just an
        // addition:  simSum[a+b][c] = simSum[a][c] + simSum[b][c].
        // Divide by the member counts when you actually need the average.
        val simSum = Array(n) { i -> pairSim[i].copyOf() }

        while (true) {
            var bestA = -1
            var bestB = -1
            var bestAvg = threshold

            for (a in 0 until n) {
                if (!alive[a]) continue
                for (b in a + 1 until n) {
                    if (!alive[b]) continue
                    if (blocked[a][b]) continue
                    val avg = simSum[a][b] / (members[a].size * members[b].size)
                    if (avg > bestAvg) {
                        bestAvg = avg
                        bestA = a
                        bestB = b
                    }
                }
            }

            if (bestA < 0) break

            members[bestA].addAll(members[bestB])
            alive[bestB] = false
            for (c in 0 until n) {
                if (!alive[c] || c == bestA) continue
                simSum[bestA][c] += simSum[bestB][c]
                simSum[c][bestA] = simSum[bestA][c]

                // The constraint has to be inherited, otherwise it leaks away
                // after a couple of merges: if B could not join C, then A+B
                // cannot either.
                if (blocked[bestB][c]) {
                    blocked[bestA][c] = true
                    blocked[c][bestA] = true
                }
            }
        }

        return (0 until n)
            .filter { alive[it] && members[it].size >= minClusterSize }
            .map { idx -> members[idx].sorted().map { items[it] } }
    }
}
