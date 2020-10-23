/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.contacts.groups.details

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import ch.protonmail.android.R
import ch.protonmail.android.activities.BaseActivity
import ch.protonmail.android.contacts.groups.ContactGroupEmailsAdapter
import ch.protonmail.android.contacts.groups.edit.ContactGroupEditCreateActivity
import ch.protonmail.android.core.ProtonMailApplication
import ch.protonmail.android.events.LogoutEvent
import ch.protonmail.android.utils.AppUtil
import ch.protonmail.android.utils.Event
import ch.protonmail.android.utils.UiUtil
import ch.protonmail.android.utils.extensions.showToast
import ch.protonmail.android.utils.moveToLogin
import ch.protonmail.android.utils.ui.RecyclerViewEmptyViewSupport
import ch.protonmail.android.utils.ui.dialogs.DialogUtils
import com.squareup.otto.Subscribe
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_contact_group_details.*
import kotlinx.android.synthetic.main.content_contact_group_details.*
import timber.log.Timber
import javax.inject.Inject

// region constants
const val EXTRA_CONTACT_GROUP = "extra_contact_group"
// endregion

@AndroidEntryPoint
class ContactGroupDetailsActivity : BaseActivity() {

    @Inject
    lateinit var contactGroupDetailsViewModelFactory: ContactGroupDetailsViewModelFactory
    private lateinit var contactGroupDetailsViewModel: ContactGroupDetailsViewModel
    private lateinit var contactGroupEmailsAdapter: ContactGroupEmailsAdapter
    private var name = " "
    private var size = 0

    override fun getLayoutId() = R.layout.activity_contact_group_details

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSupportActionBar(animToolbar)
        if (supportActionBar != null)
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        contactGroupDetailsViewModel =
                ViewModelProvider(this, contactGroupDetailsViewModelFactory)
                    .get(ContactGroupDetailsViewModel::class.java)
        initAdapter()
        startObserving()
        val bundle = intent?.getBundleExtra(EXTRA_CONTACT_GROUP)
        contactGroupDetailsViewModel.setData(bundle?.getParcelable(EXTRA_CONTACT_GROUP))

        initFilterView()
        editFab.setOnClickListener {
            val intent = Intent(this, ContactGroupEditCreateActivity::class.java)
            intent.putExtra(EXTRA_CONTACT_GROUP, contactGroupDetailsViewModel.getData() as Parcelable)
            startActivity(AppUtil.decorInAppIntent(intent))
        }
    }

    override fun onStart() {
        super.onStart()
        ProtonMailApplication.getApplication().bus.register(this)
    }

    override fun onStop() {
        super.onStop()
        ProtonMailApplication.getApplication().bus.unregister(this)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.delete_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    private fun initAdapter() {
        contactGroupEmailsAdapter = ContactGroupEmailsAdapter(this, ArrayList(), null)
        with(contactGroupEmailsAdapter) {
            registerAdapterDataObserver(
                RecyclerViewEmptyViewSupport(
                    contactEmailsRecyclerView,
                    noResults
                )
            )
        }
        with(contactEmailsRecyclerView) {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@ContactGroupDetailsActivity)
            adapter = contactGroupEmailsAdapter
        }
    }

    private fun initCollapsingToolbar(
        color: Int,
        name: String,
        emailsCount: Int
    ) {
        collapsingToolbar.apply {
            setBackgroundColor(color)
            setContentScrimColor(color)
            setStatusBarScrimColor(color)
            title = name
            setTitle(name, size)
        }
    }

    private fun setTitle(
        name: String?,
        emailsCount: Int
    ) {
        collapsingToolbar.title = ""

        name?.let {
            val titleFormatted = String.format(
                getString(R.string.contact_group_toolbar_title),
                name,
                resources.getQuantityString(
                    R.plurals.contact_group_members,
                    emailsCount,
                    emailsCount
                )
            )
            collapsingToolbar.title = titleFormatted
        }

    }

    private fun startObserving() {
        contactGroupDetailsViewModel.contactGroupEmailsResult.observe(this, {
            contactGroupEmailsAdapter.setData(it ?: ArrayList())
            if (it != null && TextUtils.isEmpty(filterView.text.toString())) {
                this.name = contactGroupDetailsViewModel.getData()?.name.toString()
                this.size = it.size
                setTitle(contactGroupDetailsViewModel.getData()?.name, it.size)
            }
        })
        contactGroupDetailsViewModel.contactGroupEmailsEmpty.observe(
                this,
                Observer<Event<String>> {
                    contactGroupEmailsAdapter.setData(ArrayList())
                })
        contactGroupDetailsViewModel.setupUIData.observe(this, Observer {
            val colorString = UiUtil.normalizeColor(it?.color)
            val color = Color.parseColor(colorString)
            this.name = it!!.name
            this.size = it.contactEmailsCount
            initCollapsingToolbar(color, it.name, it.contactEmailsCount)
        })

        contactGroupDetailsViewModel.deleteGroupStatus.observe(this, {
            it?.getContentIfNotHandled()?.let { status ->
                Timber.v("deleteGroupStatus received $status")
                when (status) {
                    ContactGroupDetailsViewModel.Status.SUCCESS -> {
                        saveLastInteraction()
                        finish()
                        showToast(resources.getQuantityString(R.plurals.group_deleted, 1))
                    }
                    ContactGroupDetailsViewModel.Status.ERROR -> showToast(status.message ?: getString(R.string.error))
                }

            }
        })
    }

    private fun initFilterView() {
        filterView.apply {
            UiUtil.setTextViewDrawableColor(this@ContactGroupDetailsActivity, this, R.color.lead_gray)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}

                override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}

                override fun afterTextChanged(editable: Editable?) {
                    contactGroupDetailsViewModel.doFilter(filterView.text.toString())
                }
            })
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        setTitle(this.name, this.size)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> consume { onBackPressed() }
        R.id.action_delete -> consume {
            DialogUtils.showDeleteConfirmationDialog(
                this, getString(R.string.delete),
                resources.getQuantityString(R.plurals.are_you_sure_delete_group, 1))
            {
                contactGroupDetailsViewModel.delete()
            }
        }
        else -> super.onOptionsItemSelected(item)
    }

    private inline fun consume(f: () -> Unit): Boolean {
        f()
        return true
    }

    override fun onBackPressed() {
        setResult(Activity.RESULT_OK)
        saveLastInteraction()
        finish()
    }

    @Subscribe
    @Suppress("unused", "UNUSED_PARAMETER")
    fun onLogoutEvent(event: LogoutEvent?) {
        moveToLogin()
    }
}
