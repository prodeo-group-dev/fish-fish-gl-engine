package com.theprodeogroup.fish.domain.common

/**
 * Generic paginated result for API responses
 * 
 * Wraps a list of items with pagination metadata
 * 
 * Example usage:
 * ```
 * fun getJournals(limit: Int, offset: Int): PaginatedResult<JournalEntry> {
 *     val journals = repository.findAll(limit, offset)
 *     val total = repository.count()
 *     return PaginatedResult.of(journals, total, limit, offset)
 * }
 * ```
 * 
 * @param T Type of items in the result
 */
data class PaginatedResult<T>(
    /**
     * List of items in this page
     */
    val items: List<T>,
    
    /**
     * Total number of items across all pages
     */
    val total: Long,
    
    /**
     * Maximum number of items per page
     */
    val limit: Int,
    
    /**
     * Number of items skipped (page offset)
     */
    val offset: Int,
    
    /**
     * True if there are more pages after this one
     */
    val hasMore: Boolean
) {
    /**
     * Current page number (1-indexed)
     */
    val currentPage: Int
        get() = (offset / limit) + 1
    
    /**
     * Total number of pages
     */
    val totalPages: Int
        get() = ((total + limit - 1) / limit).toInt()
    
    /**
     * Number of items in this page
     */
    val itemCount: Int
        get() = items.size
    
    /**
     * True if this is the first page
     */
    val isFirstPage: Boolean
        get() = offset == 0
    
    /**
     * True if this is the last page
     */
    val isLastPage: Boolean
        get() = !hasMore
    
    companion object {
        /**
         * Creates a paginated result from a list of items
         * 
         * @param items List of items in this page
         * @param total Total number of items across all pages
         * @param limit Maximum items per page
         * @param offset Number of items skipped
         * @return PaginatedResult with calculated hasMore flag
         */
        fun <T> of(items: List<T>, total: Long, limit: Int, offset: Int): PaginatedResult<T> {
            return PaginatedResult(
                items = items,
                total = total,
                limit = limit,
                offset = offset,
                hasMore = offset + items.size < total
            )
        }
        
        /**
         * Creates an empty paginated result
         * 
         * @return PaginatedResult with no items
         */
        fun <T> empty(): PaginatedResult<T> {
            return PaginatedResult(
                items = emptyList(),
                total = 0,
                limit = 0,
                offset = 0,
                hasMore = false
            )
        }
        
        /**
         * Creates a single-page result (no pagination)
         * 
         * @param items All items (fits in one page)
         * @return PaginatedResult with all items in one page
         */
        fun <T> singlePage(items: List<T>): PaginatedResult<T> {
            return PaginatedResult(
                items = items,
                total = items.size.toLong(),
                limit = items.size,
                offset = 0,
                hasMore = false
            )
        }
    }
}
