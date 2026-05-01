package martin.cli

import com.github.ajalt.clikt.core.CliktCommand

class FowlerCommand : CliktCommand(name = "fowler") {

    override fun run() {
        val quote = QUOTES.random()
        echo("\n  \"$quote\"\n\n    -- Martin Fowler\n")
    }

    companion object {
        private val QUOTES = listOf(
            "Any fool can write code that a computer can understand.\n" +
                "Good programmers write code that humans can understand.",
            "When you find you have to add a feature to a program, and the code is not\n" +
                "structured in a convenient way to add the feature, first refactor the program\n" +
                "to make it easy to add the feature, then add the feature.",
            "If it hurts, do it more frequently, and bring the pain forward.",
            "The key to keeping code readable is refactoring -- early, often, and mercilessly.",
            "Whenever I have to think to understand what the code is doing,\n" +
                "I ask myself if I can refactor the code to make that understanding\n" +
                "more immediately apparent.",
            "The whole point of refactoring is to make us program faster,\n" +
                "producing more value with less effort.",
        )
    }
}
