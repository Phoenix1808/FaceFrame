package com.example.faceframe.processing

/**
 * Embeddings ko groups mein baantta hai - ek group = ek insaan.
 *
 * ALGORITHM: agglomerative (hierarchical) clustering, average linkage.
 *
 *   1. Shuru mein har item apna alag cluster
 *   2. Sabse milte-julte do clusters dhoondo
 *   3. Unki similarity threshold se upar hai? -> merge karo, step 2 dohrao
 *   4. Nahi? -> ruk jao, ab koi merge karne layak nahi bacha
 *
 * K-Means kyun NAHI: usko pehle se batana padta hai kitne clusters banane hain.
 * Humein pata hi nahi ki video mein kitne log hain - wahi to pata karna hai.
 * (Aur "5 log hain" likh dena assignment ke "do not hardcode" ke khilaf hota.)
 * Yahan threshold khud tay karta hai kitne clusters banenge.
 *
 * AVERAGE LINKAGE kyun: do clusters ki similarity = unke saare cross-pairs ka
 * average. Single linkage (sabse best pair) mein ek galat match poore do
 * clusters ko jod deta hai - "chaining problem".
 *
 * Ye generic hai kyunki hum Tracklets cluster karte hain, per-frame samples
 * nahi. Tracklet ka embedding uske saare frames ka average hota hai, jo ek
 * frame ke embedding se kaafi zyada stable hai.
 */
object FaceClusterer {

    /**
     * @param embeddingOf har item ka L2-normalized embedding
     * @param minClusterSize isse chhote clusters kachra maan kar hata diye jaate hain
     * @return clusters, har ek ke andar items input ke order mein
     */
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

        // Har jodi ki similarity ek baar hi nikaalo - baad mein sirf padhenge.
        val pairSim = Array(n) { FloatArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val s = FaceEmbedder.cosineSimilarity(embeddings[i], embeddings[j])
                pairSim[i][j] = s
                pairSim[j][i] = s
            }
        }

        // HARD CONSTRAINT: kuch jodiyan kabhi ek nahi ho sakti, chahe unke
        // embeddings kitne bhi milte hon. Do tracklets jo ek hi samay chal
        // rahe hain, pakka do alag log hain - ek insaan ek waqt me do jagah
        // nahi ho sakta. Ye baat model ke andaze se kahin zyada pakki hai,
        // aur yahi wo galti rokti hai jahan milte-julte chehre jud jaate hain.
        val blocked = Array(n) { i ->
            BooleanArray(n) { j -> i != j && cannotMerge(items[i], items[j]) }
        }

        val members = MutableList(n) { mutableListOf(it) }
        val alive = BooleanArray(n) { true }

        // simSum[a][b] = cluster a aur b ke beech saari pairwise similarities ka JOD.
        // Jod isliye (average nahi) ki merge ke waqt sirf add karna padta hai:
        //     simSum[a+b][c] = simSum[a][c] + simSum[b][c]
        // Average nikaalne ke liye members ki ginti se divide kar lete hain.
        val simSum = Array(n) { i -> pairSim[i].copyOf() }

        while (true) {
            var bestA = -1
            var bestB = -1
            var bestAvg = threshold          // isse upar wala hi merge hoga

            for (a in 0 until n) {
                if (!alive[a]) continue
                for (b in a + 1 until n) {
                    if (!alive[b]) continue
                    if (blocked[a][b]) continue          // ek waqt me do jagah nahi
                    val avg = simSum[a][b] / (members[a].size * members[b].size)
                    if (avg > bestAvg) {
                        bestAvg = avg
                        bestA = a
                        bestB = b
                    }
                }
            }

            if (bestA < 0) break             // koi jodi threshold paar nahi kar payi

            members[bestA].addAll(members[bestB])
            alive[bestB] = false
            for (c in 0 until n) {
                if (!alive[c] || c == bestA) continue
                simSum[bestA][c] += simSum[bestB][c]
                simSum[c][bestA] = simSum[bestA][c]

                // Constraint bhi wirasat me milti hai: agar B, C ke saath nahi
                // ban sakta tha, to A+B bhi C ke saath nahi ban sakta.
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
