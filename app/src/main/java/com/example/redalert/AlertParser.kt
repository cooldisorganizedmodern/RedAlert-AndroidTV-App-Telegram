package com.example.redalert

data class ParsedAlert(
    val title: String,
    val subTitle: String? = null,
    val cities: List<String>
)

object AlertParser {

    /**
     * Parses the Hebrew alert message.
     */
    fun parseHebrewAlert(rawText: String): ParsedAlert? {
        val trimmed = rawText.trim()
        if (!trimmed.startsWith("🚨") && !trimmed.startsWith("✈") && !trimmed.startsWith("🔓")) { // Block out non-relevant real time events
            return null // Not a real-time emergency alert
        }

        // 1. Extract Main Title, capturing the Emoji as well
        // Emoji/s are group 1
        val titleRegex = "([🚨✈🔓])\\s*([^()]+?)\\s*\\(".toRegex()
        val titleMatch = titleRegex.find(rawText)

        val emoji = titleMatch?.groups?.get(1)?.value ?: ""
        val textTitle = titleMatch?.groups?.get(2)?.value?.trim() ?: "התרעה"

        // מחברים את האימוג'י חזרה לטקסט
        val title = "$emoji $textTitle".trim()
        
        // 2. Extract Subtitle (if generic) and Cities
        val lines = rawText.split("\n")
        val citiesList = mutableListOf<String>()
        var subTitle: String? = null
        
        val isGenericTitle = textTitle == "עדכון" || textTitle == "מבזק"
        var lookingForSubtitle = isGenericTitle
        
        // Skip the first line as it's the main title
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            
            // Skip empty lines or known non-city lines
            if (line.isEmpty()) continue
            
            if (line.startsWith("אזור")) {
                lookingForSubtitle = false // Stop looking once we hit regions
                continue
            }
            if (line.contains("האירוע הסתיים")) {
                if (lookingForSubtitle) {
                    subTitle = line
                    lookingForSubtitle = false
                }
                continue
            }
            if (line.contains("בדקות הקרובות צפויות להתקבל")) {
                if (lookingForSubtitle) {
                    subTitle = line
                    lookingForSubtitle = false
                }
                continue // general info
            }
            if (line.contains("מרחב המוגן") || line.contains("היכנסו") || line.contains("פיקוד העורף")) {
                continue
            }
            
            if (lookingForSubtitle) {
                // First valid descriptive line becomes the subtitle
                subTitle = line
                lookingForSubtitle = false
                continue
            }
            
            // Assume the remaining lines contain comma-separated cities
            val parts = line.split(",")
            for (p in parts) {
                var city = p.trim()
                if (city.isNotEmpty()) {
                    // Remove additional info for cities like estimated time"
                    city = city.replace(Regex("\\s*\\(.*?\\)"), "")
                    citiesList.add(city.trim())
                }
            }
        }
        
        if (citiesList.isEmpty() && subTitle == null) {
            // Some alerts like "עדכון" or "מבזק" might not have cities or a subtitle.
            // We can still display the title.
        }

        return ParsedAlert(title, subTitle, citiesList)
    }
}
