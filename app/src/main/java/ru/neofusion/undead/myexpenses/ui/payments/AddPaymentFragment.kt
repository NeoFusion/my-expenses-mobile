package ru.neofusion.undead.myexpenses.ui.payments

import android.app.Activity.RESULT_OK
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProviders
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.fragment_add_payment.*
import kotlinx.android.synthetic.main.layout_edit_payment_controls.*
import ru.neofusion.undead.myexpenses.DateUtils.formatToString
import ru.neofusion.undead.myexpenses.DateUtils.formatToDate
import ru.neofusion.undead.myexpenses.DateUtils.plus
import ru.neofusion.undead.myexpenses.PaymentActivity
import ru.neofusion.undead.myexpenses.R
import ru.neofusion.undead.myexpenses.domain.Category
import ru.neofusion.undead.myexpenses.domain.Result
import ru.neofusion.undead.myexpenses.repository.network.MyExpenses
import ru.neofusion.undead.myexpenses.ui.RoublesTextWatcher
import ru.neofusion.undead.myexpenses.ui.UiHelper
import java.util.*

class AddPaymentFragment(
    private val categoryId: Int?,
    private val description: String?,
    private val seller: String?,
    private val costString: String?
) : Fragment() {

    companion object {
        fun newInstance(
            categoryId: Int?,
            description: String?,
            seller: String?,
            costString: String?
        ): AddPaymentFragment =
            AddPaymentFragment(categoryId, description, seller, costString)
    }

    private lateinit var categories: List<Category>

    private val compositeDisposable = CompositeDisposable()

    private lateinit var viewModel: AddPaymentViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_add_payment, container, false)
        retainInstance = true
        viewModel = ViewModelProviders.of(this).get(AddPaymentViewModel::class.java)
        viewModel.result.observe(this, androidx.lifecycle.Observer {
            doOnCategoriesResult(it)
        })
        return view
    }

    override fun onDetach() {
        super.onDetach()
        compositeDisposable.dispose()
    }

    private fun doOnCategoriesResult(result: Result<List<Category>>) {
        if (result is Result.Success) {
            categories = result.value.filterNot { it.hidden }
            val adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                categories.map { it.name }
            )
            spinnerCategory.adapter = adapter
            if (categories.isNotEmpty()) {
                spinnerCategory.setSelection(0)
            } else {
                UiHelper.snack(requireActivity(), getString(R.string.error_no_categories))
                requireActivity().finish()
            }
            adapter.notifyDataSetChanged()

            initControls()
        } else {
            UiHelper.snack(requireActivity(), (result as Result.Error).message)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val calendar = Calendar.getInstance()
        datePicker.setText(Date().formatToString())
        datePicker.setOnClickListener {
            DatePickerDialog(
                requireContext(),
                DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
                    calendar.set(Calendar.YEAR, year)
                    calendar.set(Calendar.MONTH, month)
                    calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    datePicker.setText(calendar.time.formatToString())
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        dateLeft.setOnClickListener {
            datePicker.text.toString().formatToDate()?.let { date ->
                datePicker.setText(date.plus(Calendar.DAY_OF_MONTH, -1).formatToString())
            }
        }
        dateRight.setOnClickListener {
            datePicker.text.toString().formatToDate()?.let { date ->
                datePicker.setText(date.plus(Calendar.DAY_OF_MONTH, 1).formatToString())
            }
        }

        addButton.setOnClickListener {
            addPayment { paymentId ->
                finishWithSuccess(paymentId)
            }
        }
        addAndCreateNewButton.setOnClickListener {
            addPayment { paymentId ->
                UiHelper.snack(requireActivity(), "Добавлен платеж $paymentId")
                clearControls()
            }
        }

        etCost.addTextChangedListener(RoublesTextWatcher(etCost))

        viewModel.subscribe(requireContext())
    }

    private fun initControls() {
        spinnerCategory.adapter.count.takeIf { it > 0 }.let {
            val index = categories.indexOfFirst { it.id == categoryId }
            spinnerCategory.setSelection(if (index != -1) index else 0)
        }
        etDescription.setText(description.orEmpty())
        etSeller.setText(seller.orEmpty())
        etCost.setText(costString.orEmpty())
    }

    private fun clearControls() {
        spinnerCategory.adapter.count.takeIf { it > 0 }.let { spinnerCategory.setSelection(0) }
        etDescription.setText("")
        etSeller.setText("")
        etCost.setText("")
    }

    private fun addPayment(doOnSuccess: (Int) -> Unit) {
        if (!areFieldsValid()) {
            return
        }

        compositeDisposable.add(
            MyExpenses.PaymentApi.addPayment(
                requireContext(),
                categories[spinnerCategory.selectedItemPosition].id,
                datePicker.text.toString().formatToDate() ?: Date(),
                etDescription.text.toString(),
                etSeller.text.toString(),
                etCost.text.toString()
            )
                .doOnSubscribe {
                    requireActivity().runOnUiThread {
                        addButton.isEnabled = false
                        addAndCreateNewButton.isEnabled = false
                    }
                }
                .doOnTerminate {
                    requireActivity().runOnUiThread {
                        addButton.isEnabled = true
                        addAndCreateNewButton.isEnabled = true
                    }
                }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ result ->
                    if (result is Result.Success) {
                        doOnSuccess.invoke(result.value)
                    } else {
                        UiHelper.snack(requireActivity(), (result as Result.Error).message)
                    }
                }, {
                    UiHelper.snack(requireActivity(), it.message ?: "Ой-ой-ой")
                })
        )
    }

    private fun finishWithSuccess(paymentId: Int) {
        requireActivity().setResult(
            RESULT_OK,
            Intent().apply { PaymentActivity.putPaymentId(this, paymentId) })
        requireActivity().finish()
    }

    private fun areFieldsValid(): Boolean {
        var result = true
        datePicker.text.takeIf { it.isNullOrEmpty() }?.let {
            datePicker.error = getString(R.string.error_empty)
            result = false
        }
        etCost.text.takeIf { it.isNullOrEmpty() }?.let {
            etCost.error = getString(R.string.error_empty)
            result = false
        }
        return result
    }
}