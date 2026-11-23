package com.wprd.calling.ui.main

/** A MainActivity tab that can filter its list from the toolbar search. */
interface SearchableTab {
    fun applySearch(query: String)
}
