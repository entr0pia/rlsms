/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS.feature.main

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import com.moez.QKSMS.R
import com.moez.QKSMS.common.Navigator
import com.moez.QKSMS.common.base.QkAdapter
import com.moez.QKSMS.common.base.QkViewHolder
import com.moez.QKSMS.common.util.Colors
import com.moez.QKSMS.common.util.DateFormatter
import com.moez.QKSMS.common.util.extensions.setVisible
import com.moez.QKSMS.extensions.removeAccents
import com.moez.QKSMS.model.SearchResult
import kotlinx.android.synthetic.main.search_list_item.*
import kotlinx.android.synthetic.main.search_list_item.view.*
import javax.inject.Inject

class SearchAdapter @Inject constructor(
    colors: Colors,
    private val context: Context,
    private val dateFormatter: DateFormatter,
    private val navigator: Navigator
) : QkAdapter<SearchResult>() {

    private val highlightColor: Int by lazy { colors.theme().highlight }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val view = layoutInflater.inflate(R.layout.search_list_item, parent, false)
        return QkViewHolder(view).apply {
            view.setOnClickListener {
                val result = getItem(adapterPosition)
                navigator.showConversation(
                    threadId = result.conversation.id,
                    query = result.query.takeIf { result.messages > 0 },
                    messageId = result.message?.id
                )
            }
        }
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        val result = getItem(position)

        holder.resultsHeader.setVisible(false)

        val query = result.query
        val title = SpannableString(result.conversation.getTitle())
        var index = title.removeAccents().indexOf(query, ignoreCase = true)

        while (index >= 0) {
            title.setSpan(BackgroundColorSpan(highlightColor), index, index + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            index = title.indexOf(query, index + query.length, true)
        }
        holder.title.text = title

        holder.avatars.recipients = result.conversation.recipients

        holder.date.setVisible(result.date > 0)
        if (result.date > 0) {
            holder.date.text = dateFormatter.getConversationTimestamp(result.date)
        }

        val rawSnippet = when {
            result.message != null -> result.snippet
            result.conversation.me -> context.getString(R.string.main_sender_you, result.snippet)
            else -> result.snippet
        }

        val snippetSpannable = SpannableString(rawSnippet)
        var snippetIndex = snippetSpannable.removeAccents().indexOf(query, ignoreCase = true)
        while (snippetIndex >= 0) {
            snippetSpannable.setSpan(
                BackgroundColorSpan(highlightColor),
                snippetIndex,
                snippetIndex + query.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            snippetIndex = snippetSpannable.indexOf(query, snippetIndex + query.length, true)
        }
        holder.snippet.text = snippetSpannable
    }

    override fun areItemsTheSame(old: SearchResult, new: SearchResult): Boolean {
        return old.conversation.id == new.conversation.id && old.message?.id == new.message?.id
    }

    override fun areContentsTheSame(old: SearchResult, new: SearchResult): Boolean {
        return old.query == new.query &&
                old.conversation.id == new.conversation.id &&
                old.message?.id == new.message?.id &&
                old.date == new.date &&
                old.snippet == new.snippet &&
                old.conversation.getTitle() == new.conversation.getTitle()
    }
}