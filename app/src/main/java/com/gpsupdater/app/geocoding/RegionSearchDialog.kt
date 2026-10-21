package com.gpsupdater.app.geocoding

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.gpsupdater.app.R
import com.gpsupdater.app.databinding.DialogRegionSearchBinding
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

/** Offline browsing is the primary flow; free-text geocoding is an explicit fallback. */
class RegionSearchDialog(
    private val context: Context,
    private val geocoder: GeocodingService,
    private val onSelected: (GeocodingResult) -> Unit,
) {
    fun show() {
        val binding = DialogRegionSearchBinding.inflate(LayoutInflater.from(context))
        val directory = RegionDirectory(context)
        val locale = context.resources.configuration.locales[0]
        val countries = directory.countries()
        val countryNames = countries.associate { country ->
            val localized = Locale("", country.code).getDisplayCountry(locale)
            country.code to localized.takeUnless { it.isBlank() || it == country.code }.orEmpty()
                .ifBlank { country.name }
        }
        val preferences = context.getSharedPreferences("region_directory", Context.MODE_PRIVATE)
        var country = countries.firstOrNull { it.code == preferences.getString("country", null) }
            ?: countries.firstOrNull { it.code == Locale.getDefault().country }
            ?: countries.first { it.code == "CN" }
        var subdivisions = emptyList<RegionSubdivision>()
        var state: RegionSubdivision? = null
        var district: RegionPlace? = null
        var city: RegionPlace? = null
        var loading = false
        var failed = false
        var generation = 0
        val executor = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.region_search)
            .setView(binding.root)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.directory_keyword) { _, _ ->
                PlaceKeywordSearchDialog(context, geocoder, onSelected, country.code).show()
            }
            .create()
        // Keep the accessible country field backed by the complete country catalogue.
        binding.countryInput.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line,
            countries.map { CountryOption(it.code, countryNames.getValue(it.code)) }))

        fun update() {
            binding.countryInput.setText(countryNames.getValue(country.code), false)
            binding.stateInput.setText(state?.place?.label.orEmpty(), false)
            binding.districtInput.setText(district?.label.orEmpty(), false)
            binding.cityInput.setText(city?.label.orEmpty(), false)
            binding.stateLayout.isEnabled = !loading && subdivisions.isNotEmpty()
            binding.districtLayout.isEnabled = !loading && state?.districts?.isNotEmpty() == true
            binding.cityLayout.isEnabled = !loading && state?.citiesIn(district)?.isNotEmpty() == true
            binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
            val selectedPlace = city ?: district ?: state?.place
            val coordinates = if (selectedPlace == null) country.coordinates else selectedPlace.coordinates
            binding.searchButton.isEnabled = !loading && coordinates != null
            binding.coverageText.text = when {
                loading -> context.getString(R.string.directory_loading)
                failed -> context.getString(R.string.directory_failed)
                coordinates == null -> context.getString(R.string.directory_no_coordinates)
                subdivisions.isEmpty() -> context.getString(R.string.directory_no_regions)
                state == null -> context.getString(R.string.directory_choose_state)
                district != null && state!!.citiesIn(district).isEmpty() ->
                    context.getString(R.string.directory_no_cities)
                else -> context.getString(R.string.directory_counts,
                    state!!.districts.size, state!!.citiesIn(district).size)
            }
        }

        fun loadCountry(restore: Boolean = false) {
            val request = ++generation
            val code = country.code
            state = null
            district = null
            city = null
            subdivisions = emptyList()
            loading = true
            failed = false
            update()
            executor.execute {
                val result = runCatching { directory.regions(code) }
                handler.post {
                    if (!dialog.isShowing || request != generation) return@post
                    loading = false
                    failed = result.isFailure
                    subdivisions = result.getOrDefault(emptyList())
                    if (restore) {
                        state = subdivisions.firstOrNull { it.place.id == preferences.getInt("state", -1) }
                        district = state?.districts?.firstOrNull { it.id == preferences.getInt("district", -1) }
                        city = state?.citiesIn(district)?.firstOrNull { it.id == preferences.getInt("city", -1) }
                    }
                    update()
                }
            }
        }

        fun chooseCountry() = pick(R.string.country, countries, { countryNames.getValue(it.code) }) { chosen ->
            if (chosen != null && country.code != chosen.code) {
                country = chosen
                loadCountry()
            }
        }
        fun chooseState() = pick(R.string.directory_state, subdivisions, { it.place.label }, true) { chosen ->
            state = chosen
            district = null
            city = null
            update()
        }
        fun chooseDistrict() {
            val districts = state?.districts.orEmpty()
            pick(R.string.directory_district, districts, { item ->
                val parent = districts.firstOrNull { it.id == item.parentId }
                if (parent == null) item.label else "${item.label} — ${parent.label}"
            }, true) { chosen ->
                district = chosen
                city = null
                update()
            }
        }
        fun chooseCity() = pick(R.string.directory_city, state?.citiesIn(district).orEmpty(), { it.label }, true) {
            city = it
            update()
        }
        binding.countryInput.setOnClickListener { chooseCountry() }
        binding.countryLayout.setEndIconOnClickListener { chooseCountry() }
        binding.stateInput.setOnClickListener { chooseState() }
        binding.stateLayout.setEndIconOnClickListener { chooseState() }
        binding.districtInput.setOnClickListener { chooseDistrict() }
        binding.districtLayout.setEndIconOnClickListener { chooseDistrict() }
        binding.cityInput.setOnClickListener { chooseCity() }
        binding.cityLayout.setEndIconOnClickListener { chooseCity() }
        binding.coverageText.setOnClickListener { if (failed && !loading) loadCountry() }
        binding.searchButton.setOnClickListener {
            val place = city ?: district ?: state?.place
            val point = if (place == null) country.coordinates else place.coordinates
            if (point != null) {
                val name = place?.label ?: countryNames.getValue(country.code)
                val path = listOfNotNull(city?.label, district?.label, state?.place?.label,
                    countryNames.getValue(country.code)).distinct().joinToString(", ")
                val zoom = when { city != null -> 13.0; district != null -> 11.0; state != null -> 8.0; else -> 5.0 }
                preferences.edit().putString("country", country.code)
                    .putInt("state", state?.place?.id ?: -1)
                    .putInt("district", district?.id ?: -1)
                    .putInt("city", city?.id ?: -1).apply()
                dialog.dismiss()
                onSelected(GeocodingResult(name, path, point, zoom))
            }
        }
        dialog.setOnDismissListener {
            generation += 1
            executor.shutdownNow()
            handler.removeCallbacksAndMessages(null)
        }
        dialog.show()
        loadCountry(restore = true)
    }

    private fun <T> pick(
        title: Int,
        items: List<T>,
        label: (T) -> String,
        allowClear: Boolean = false,
        onPicked: (T?) -> Unit,
    ) {
        val collator = Collator.getInstance(context.resources.configuration.locales[0])
        val ordered = items.sortedWith { a, b -> collator.compare(label(a), label(b)) }
        var visible = ordered
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val searchLayout = TextInputLayout(context).apply { hint = context.getString(R.string.directory_filter) }
        val search = TextInputEditText(context).apply {
            id = R.id.directoryFilter
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        searchLayout.addView(search)
        content.addView(searchLayout)
        val empty = TextView(context).apply {
            text = context.getString(R.string.directory_no_matches)
            setPadding(0, dp(16), 0, dp(16))
            visibility = View.GONE
        }
        content.addView(empty)
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, ordered.map(label).toMutableList())
        val list = ListView(context).apply {
            id = R.id.directoryList
            this.adapter = adapter
            dividerHeight = 0
        }
        content.addView(list, LinearLayout.LayoutParams(-1,
            minOf(dp(320), (context.resources.displayMetrics.heightPixels * 0.4).toInt())))
        val builder = MaterialAlertDialogBuilder(context).setTitle(title).setView(content)
            .setNegativeButton(R.string.close, null)
        if (allowClear) builder.setNeutralButton(R.string.directory_skip) { _, _ -> onPicked(null) }
        val picker = builder.create()
        list.setOnItemClickListener { _, _, position, _ ->
            val chosen = visible[position]
            picker.dismiss()
            onPicked(chosen)
        }
        search.doAfterTextChanged { query ->
            visible = ordered.filter { RegionDirectory.matches(label(it), query.toString()) }
            adapter.clear()
            adapter.addAll(visible.map(label))
            empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        }
        content.requestFocus()
        picker.show()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
