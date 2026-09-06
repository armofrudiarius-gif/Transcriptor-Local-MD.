package md.localtranscript

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLexiconTest {
    @Test fun preferredDiacriticsAndCaseAreApplied() {
        val terms = LocalLexicon.parse("CNA, Chișinău, stroică, spravcă")
        assertEquals(
            "CNA din Chișinău, stroică și spravcă",
            LocalLexicon.applyPreferredForms("cna din Chisinau, stroica și spravca", terms)
        )
    }

    @Test fun unrelatedWordsAreNotChanged() {
        val terms = LocalLexicon.parse("stroică")
        assertEquals("construcția este mare", LocalLexicon.applyPreferredForms("construcția este mare", terms))
    }
}
