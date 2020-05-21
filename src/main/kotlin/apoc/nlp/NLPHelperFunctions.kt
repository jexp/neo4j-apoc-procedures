package apoc.nlp

import apoc.util.Util
import org.neo4j.graphdb.Node
import org.neo4j.graphdb.Relationship
import org.neo4j.graphdb.RelationshipType
import org.neo4j.graphdb.Transaction
import java.util.stream.Stream

class NLPHelperFunctions {
    companion object {
        fun createRelationshipsNoScore(node: Node, nodesAndScores: Set<Node>, relationshipType: RelationshipType) =
                nodesAndScores.map { n ->
                    node.createRelationshipTo(n, relationshipType)
                }

        fun createRelationships(node: Node, nodesAndScores: List<Pair<Node, Number>>, relationshipType: RelationshipType) =
                nodesAndScores.map { (n, score) ->
                    val relationship = node.createRelationshipTo(n, relationshipType)
                    relationship.setProperty("score", score)
                    relationship
                }

        fun createRelationship(node: Node, nodesAndScore: Pair<Node, Number>, relationshipType: RelationshipType): Relationship {
            val relationship = node.createRelationshipTo(nodesAndScore.first, relationshipType)
            relationship.setProperty("score", nodesAndScore.second)
            return relationship
        }

        fun mergeRelationship(transaction: Transaction, node: Node, nodeAndScores: Pair<Node, Number>, relType: RelationshipType): Stream<Relationship> {
            val cypher = """WITH ${'$'}startNode as startNode, ${'$'}endNode as endNode, ${'$'}score as score
            MERGE (startNode)-[r:${Util.quote(relType.name())}]->(endNode)
            SET r.score = score
            RETURN r"""

            val params = mapOf("startNode" to node, "endNode" to nodeAndScores.first, "score" to nodeAndScores.second)
            return transaction.execute(cypher, params).columnAs<Relationship>("r").stream()
        }


        fun mergeRelationships(transaction: Transaction, node: Node, nodesAndScores: List<Pair<Node, Float>>, relType: RelationshipType): Stream<Relationship> {
            val cypher = """WITH ${'$'}startNode as startNode, ${'$'}endNodes as endNodes
            UNWIND endNodes AS endNode
            WITH endNode.node AS node, endNode.score AS score
            MERGE (startNode)-[r:${Util.quote(relType.name())}]->(node)
            SET r.score = score
            RETURN r"""

            val params = mapOf("startNode" to node, "endNodes" to nodesAndScores.map { (node, score) -> mapOf("node" to node, "score" to score)  })
            return transaction.execute(cypher, params).columnAs<Relationship>("r").stream()
        }

        fun mergeRelationshipNoScore(transaction: Transaction, node: Node, nodesAndScores: Set<Node>, relType: RelationshipType): Stream<Relationship> {
            val cypher = """WITH ${'$'}startNode as startNode, ${'$'}endNodes as endNodes
            UNWIND endNodes AS endNode
            WITH endNode.node AS node
            MERGE (startNode)-[r:${Util.quote(relType.name())}]->(node)
            RETURN r"""

            val params = mapOf("startNode" to node, "endNodes" to nodesAndScores.map { node -> mapOf("node" to node)  })
            return transaction.execute(cypher, params).columnAs<Relationship>("r").stream()
        }

        fun entityRelationshipType(config: Map<String, Any>): RelationshipType  {
            val selectedType = getSelectedType(config, listOf("writeRelationshipType", "relationshipType"), "ENTITY")
            return RelationshipType.withName(selectedType.toString())
        }

        fun categoryRelationshipType(config: Map<String, Any>): RelationshipType {
            val selectedType = getSelectedType(config, listOf("writeRelationshipType", "relationshipType"), "CATEGORY")
            return RelationshipType.withName(selectedType.toString())
        }

        fun keyPhraseRelationshipType(config: Map<String, Any>): RelationshipType {
            val selectedType = getSelectedType(config, listOf("writeRelationshipType", "relationshipType"), "KEY_PHRASE")
            return RelationshipType.withName(selectedType.toString())
        }
        fun getNodeProperty(config: Map<String, Any>): String {
            return config.getOrDefault("nodeProperty", "text").toString()
        }

        private fun getSelectedType(config: Map<String, Any>, keys: List<String>, default: String) =
                (keys.map { key -> config[key] } + default).filterNotNull().first()

        fun verifySource(source: Any) {
            when (source) {
                is Node -> return
                is List<*> -> source.forEach { item ->
                    if (item !is Node) {
                        throw java.lang.IllegalArgumentException("`source` must be a node or list of nodes, but was: `${source}`")
                    }
                }
                else -> throw java.lang.IllegalArgumentException("`source` must be a node or list of nodes, but was: `${source}`")
            }
        }

        fun verifyNodeProperty(source: Any, nodeProperty: String) {
            when (source) {
                is Node -> verifyNodeProperty(source, nodeProperty)
                is List<*> -> source.forEach { node -> verifyNodeProperty(node as Node, nodeProperty) }
                else -> throw java.lang.IllegalArgumentException("`source` must be a node or list of nodes, but was: `${source}`")
            }
        }

        fun verifyKey(config: Map<String, Any>, property: String) {
            if (!config.containsKey(property)) {
                throw IllegalArgumentException("Missing parameter `$property`. An API key for the Amazon Comprehend API can be generated from https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/setup-credentials.html")
            }
        }

        private fun verifyNodeProperty(node: Node, nodeProperty: String) {
            if (!node.hasProperty(nodeProperty)) {
                throw IllegalArgumentException("$node does not have property `$nodeProperty`. Property can be configured using parameter `nodeProperty`.")
            }
        }

        fun convert(source: Any): List<Node> {
            return when (source) {
                is Node -> listOf(source)
                is List<*> -> source.map { item -> item as Node }
                else -> throw java.lang.IllegalArgumentException("`source` must be a node or list of nodes, but was: `${source}`")
            }
        }

        fun partition(nodes: List<Node>, size: Int): List<List<Node>> {
            if(size < 1) throw java.lang.IllegalArgumentException("size must be >= 1, but was:$size")

            var count = 0
            val result: MutableList<List<Node>> = mutableListOf()

            while(count < nodes.size) {
                result.add(nodes.subList(count, nodes.size.coerceAtMost(count + size)))
                count += size
            }

            return result
        }

    }
}