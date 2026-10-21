package com.gpsupdater.app.geocoding

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gpsupdater.app.R
import com.gpsupdater.app.databinding.DialogPlaceKeywordSearchBinding
import java.util.Locale

class PlaceKeywordSearchDialog(
    private val context: Context,
    private val geocoder: GeocodingService,
    private val onSelected: (GeocodingResult) -> Unit,
    private val initialCountryCode: String? = null,
) {
    fun show() {
        val binding = DialogPlaceKeywordSearchBinding.inflate(LayoutInflater.from(context))
        val locale = context.resources.configuration.locales[0]
        val countries = CountryOption.localizedCountries(locale).toMutableList().apply {
            if (initialCountryCode != null && none { it.code == initialCountryCode }) {
                add(CountryOption(initialCountryCode, Locale("", initialCountryCode).getDisplayCountry(locale)))
            }
        }
        var selectedCountry = countries.firstOrNull { it.code == initialCountryCode } ?: defaultCountry(countries)
        val mainHandler = Handler(Looper.getMainLooper())
        val countryAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_dropdown_item_1line,
            countries,
        )
        binding.countryInput.setAdapter(countryAdapter)
        binding.countryInput.setText(selectedCountry.name, false)

        val citySuggestionsAdapter = PlacesAdapter(context)
        val districtSuggestionsAdapter = PlacesAdapter(context)
        val resultsAdapter = PlacesAdapter(context)
        binding.cityInput.setAdapter(citySuggestionsAdapter)
        binding.districtInput.setAdapter(districtSuggestionsAdapter)
        binding.resultsList.adapter = resultsAdapter
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.region_search)
            .setView(binding.root)
            .setNegativeButton(R.string.close, null)
            .create()

        var suppressSuggestionRequests = false
        var cityRequestVersion = 0
        var districtRequestVersion = 0
        var pendingCityRequest: Runnable? = null
        var pendingDistrictRequest: Runnable? = null

        fun countryFromInput(): CountryOption? = countries.firstOrNull {
            it.name == binding.countryInput.text?.toString()
        }

        fun clearDependentInputs() {
            pendingCityRequest?.let(mainHandler::removeCallbacks)
            pendingDistrictRequest?.let(mainHandler::removeCallbacks)
            suppressSuggestionRequests = true
            binding.cityInput.setText("", false)
            binding.districtInput.setText("", false)
            suppressSuggestionRequests = false
            citySuggestionsAdapter.submit(emptyList())
            districtSuggestionsAdapter.submit(emptyList())
            cityRequestVersion += 1
            districtRequestVersion += 1
        }

        fun scheduleCitySuggestions(rawQuery: String) {
            pendingCityRequest?.let(mainHandler::removeCallbacks)
            val query = rawQuery.trim()
            val country = countryFromInput()
            val requestVersion = ++cityRequestVersion
            mainHandler.post {
                if (
                    requestVersion == cityRequestVersion &&
                    binding.cityInput.text?.toString()?.trim() == query
                ) {
                    citySuggestionsAdapter.submit(emptyList())
                }
            }
            if (query.length < MIN_SUGGESTION_LENGTH || country == null) return

            pendingCityRequest = Runnable {
                geocoder.suggestCities(country, query, locale.toLanguageTag()) { result ->
                    if (
                        !dialog.isShowing ||
                        requestVersion != cityRequestVersion ||
                        binding.cityInput.text?.toString()?.trim() != query
                    ) {
                        return@suggestCities
                    }
                    result.onSuccess { locations ->
                        citySuggestionsAdapter.submit(locations)
                        if (locations.isNotEmpty() && binding.cityInput.hasFocus()) {
                            binding.cityInput.showDropDown()
                        }
                    }
                }
            }.also { mainHandler.postDelayed(it, SUGGESTION_DELAY_MS) }
        }

        fun scheduleDistrictSuggestions(rawQuery: String) {
            pendingDistrictRequest?.let(mainHandler::removeCallbacks)
            val query = rawQuery.trim()
            val country = countryFromInput()
            val city = binding.cityInput.text?.toString().orEmpty().trim()
            val requestVersion = ++districtRequestVersion
            mainHandler.post {
                if (
                    requestVersion == districtRequestVersion &&
                    binding.districtInput.text?.toString()?.trim() == query
                ) {
                    districtSuggestionsAdapter.submit(emptyList())
                }
            }
            if (query.length < MIN_SUGGESTION_LENGTH || country == null) return

            pendingDistrictRequest = Runnable {
                geocoder.suggestDistricts(
                    country,
                    city,
                    query,
                    locale.toLanguageTag(),
                ) { result ->
                    if (
                        !dialog.isShowing ||
                        requestVersion != districtRequestVersion ||
                        binding.districtInput.text?.toString()?.trim() != query
                    ) {
                        return@suggestDistricts
                    }
                    result.onSuccess { locations ->
                        districtSuggestionsAdapter.submit(locations)
                        if (locations.isNotEmpty() && binding.districtInput.hasFocus()) {
                            binding.districtInput.showDropDown()
                        }
                    }
                }
            }.also { mainHandler.postDelayed(it, SUGGESTION_DELAY_MS) }
        }

        binding.countryInput.setOnItemClickListener { parent, _, position, _ ->
            selectedCountry = parent.getItemAtPosition(position) as CountryOption
            binding.countryLayout.error = null
            clearDependentInputs()
            binding.cityInput.requestFocus()
        }
        binding.cityInput.doAfterTextChanged { text ->
            if (suppressSuggestionRequests) return@doAfterTextChanged
            suppressSuggestionRequests = true
            binding.districtInput.setText("", false)
            suppressSuggestionRequests = false
            districtSuggestionsAdapter.submit(emptyList())
            districtRequestVersion += 1
            scheduleCitySuggestions(text?.toString().orEmpty())
        }
        binding.districtInput.doAfterTextChanged { text ->
            if (!suppressSuggestionRequests) {
                scheduleDistrictSuggestions(text?.toString().orEmpty())
            }
        }
        binding.cityInput.setOnItemClickListener { parent, _, position, _ ->
            val location = parent.getItemAtPosition(position) as GeocodingResult
            pendingCityRequest?.let(mainHandler::removeCallbacks)
            pendingDistrictRequest?.let(mainHandler::removeCallbacks)
            suppressSuggestionRequests = true
            binding.cityInput.setText(location.name, false)
            binding.districtInput.setText("", false)
            suppressSuggestionRequests = false
            citySuggestionsAdapter.submit(emptyList())
            districtSuggestionsAdapter.submit(emptyList())
            cityRequestVersion += 1
            districtRequestVersion += 1
            binding.districtInput.requestFocus()
        }
        binding.districtInput.setOnItemClickListener { parent, _, position, _ ->
            val location = parent.getItemAtPosition(position) as GeocodingResult
            pendingDistrictRequest?.let(mainHandler::removeCallbacks)
            suppressSuggestionRequests = true
            binding.districtInput.setText(location.name, false)
            suppressSuggestionRequests = false
            districtSuggestionsAdapter.submit(emptyList())
            districtRequestVersion += 1
        }

        fun search() {
            val country = countries.firstOrNull {
                it.name == binding.countryInput.text?.toString()
            }
            if (country == null) {
                binding.countryLayout.error = context.getString(R.string.country_required)
                return
            }
            selectedCountry = country
            binding.searchButton.isEnabled = false
            binding.searchButton.setText(R.string.searching)
            binding.progress.visibility = View.VISIBLE
            binding.resultsLabel.visibility = View.GONE
            binding.resultsList.visibility = View.GONE
            binding.emptyText.visibility = View.GONE
            geocoder.search(
                selectedCountry,
                binding.cityInput.text?.toString().orEmpty(),
                binding.districtInput.text?.toString().orEmpty(),
                locale.toLanguageTag(),
            ) { result ->
                if (!dialog.isShowing) return@search
                binding.searchButton.isEnabled = true
                binding.searchButton.setText(R.string.search)
                binding.progress.visibility = View.GONE
                result.fold(
                    onSuccess = { locations ->
                        resultsAdapter.submit(locations)
                        binding.resultsLabel.visibility =
                            if (locations.isEmpty()) View.GONE else View.VISIBLE
                        binding.resultsList.visibility =
                            if (locations.isEmpty()) View.GONE else View.VISIBLE
                        binding.emptyText.setText(R.string.region_search_empty)
                        binding.emptyText.visibility =
                            if (locations.isEmpty()) View.VISIBLE else View.GONE
                    },
                    onFailure = {
                        binding.emptyText.setText(R.string.region_search_failed)
                        binding.emptyText.visibility = View.VISIBLE
                    },
                )
            }
        }

        binding.searchButton.setOnClickListener { search() }
        binding.cityInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.districtInput.requestFocus()
                true
            } else {
                false
            }
        }
        binding.districtInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else {
                false
            }
        }
        binding.resultsList.setOnItemClickListener { _, _, position, _ ->
            resultsAdapter.getItem(position)?.let(onSelected)
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            pendingCityRequest?.let(mainHandler::removeCallbacks)
            pendingDistrictRequest?.let(mainHandler::removeCallbacks)
            cityRequestVersion += 1
            districtRequestVersion += 1
        }
        dialog.show()
    }

    private fun defaultCountry(countries: List<CountryOption>): CountryOption {
        val deviceCountry = Locale.getDefault().country.ifBlank { DEFAULT_COUNTRY_CODE }
        return countries.firstOrNull { it.code.equals(deviceCountry, ignoreCase = true) }
            ?: countries.first { it.code == DEFAULT_COUNTRY_CODE }
    }

    private class PlacesAdapter(context: Context) : BaseAdapter(), Filterable {
        private val inflater = LayoutInflater.from(context)
        private var items = emptyList<GeocodingResult>()
        private val passthroughFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults = FilterResults().apply {
                values = items
                count = items.size
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                notifyDataSetChanged()
            }
        }

        fun submit(newItems: List<GeocodingResult>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): GeocodingResult = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getFilter(): Filter = passthroughFilter

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val item = getItem(position)
            view.findViewById<TextView>(android.R.id.text1).apply {
                text = item.name
                maxLines = 1
            }
            view.findViewById<TextView>(android.R.id.text2).apply {
                text = item.displayName
                maxLines = 2
            }
            return view
        }
    }

    companion object {
        private const val DEFAULT_COUNTRY_CODE = "CN"
        private const val MIN_SUGGESTION_LENGTH = 2
        private const val SUGGESTION_DELAY_MS = 450L
    }
}
