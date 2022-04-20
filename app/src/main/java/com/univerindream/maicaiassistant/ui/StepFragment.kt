package com.univerindream.maicaiassistant.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.blankj.utilcode.util.GsonUtils
import com.elvishew.xlog.XLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import com.mikepenz.fastadapter.binding.ModelAbstractBindingItem
import com.univerindream.maicaiassistant.MCCond
import com.univerindream.maicaiassistant.MCHandle
import com.univerindream.maicaiassistant.MCStep
import com.univerindream.maicaiassistant.R
import com.univerindream.maicaiassistant.databinding.FragmentStepBinding
import com.univerindream.maicaiassistant.databinding.ItemCondBinding

class StepFragment : Fragment() {
    private var _binding: FragmentStepBinding? = null

    private val binding get() = _binding!!

    private val args: StepFragmentArgs by navArgs()

    private val itemAdapter by lazy {
        ItemAdapter<BindingCondItem>()
    }

    private val fastAdapter by lazy {
        FastAdapter.with(itemAdapter)
    }

    private val mStep: MCStep by lazy {
        GsonUtils.fromJson(args.stepJson, MCStep::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setFragmentResultListener("updateHandle") { requestKey, bundle ->
            XLog.i("handle -> $requestKey $bundle")
            mStep.handle = GsonUtils.fromJson(bundle.getString("handleJson"), MCHandle::class.java)
        }

        setFragmentResultListener("updateCond") { requestKey, bundle ->
            XLog.i("handle -> $requestKey $bundle")
            val mcCond = GsonUtils.fromJson(bundle.getString("condJson"), MCCond::class.java)
            val index = bundle.getInt("condIndex")

            if (index == -1) {
                mStep.condList.add(mcCond)
            } else {
                mStep.condList[index] = mcCond
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStepBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.stepName.editText?.doAfterTextChanged { inputText ->
            val name = if (inputText.isNullOrBlank()) "" else inputText.toString()
            mStep.name = name
        }
        binding.stepCondValue.adapter = fastAdapter
        binding.stepCondValue.layoutManager = LinearLayoutManager(requireContext())
        binding.stepHandleCard.setOnClickListener {
            findNavController().navigate(
                StepFragmentDirections.actionStepFragmentToHandleFragment(
                    GsonUtils.toJson(
                        mStep.handle
                    )
                )
            )
        }
        binding.stepAlarm.setOnCheckedChangeListener { compoundButton, b ->
            if (!compoundButton.isPressed) return@setOnCheckedChangeListener
            mStep.isAlarm = b
        }
        binding.stepManual.setOnCheckedChangeListener { compoundButton, b ->
            if (!compoundButton.isPressed) return@setOnCheckedChangeListener
            mStep.isManual = b
        }
        binding.stepRepeat.setOnCheckedChangeListener { compoundButton, b ->
            if (!compoundButton.isPressed) return@setOnCheckedChangeListener
            mStep.isRepeat = b
        }
        binding.stepFailBack.setOnCheckedChangeListener { compoundButton, b ->
            binding.stepFailBackCount.isEnabled = b
            if (!compoundButton.isPressed) return@setOnCheckedChangeListener
            mStep.isFailBack = b
        }
        binding.stepFailBackCount.editText?.doAfterTextChanged { inputText ->
            val count = if (inputText.isNullOrBlank()) 1 else inputText.toString().toInt()
            mStep.failBackCount = count
        }

        binding.floatingAddButton.setOnClickListener {
            val action = StepFragmentDirections.actionStepFragmentToCondFragment(
                condIndex = -1,
                condJson = "{}"
            )
            findNavController().navigate(action)
        }
        binding.floatingSaveButton.setOnClickListener {
            saveData()
        }

        fastAdapter.onClickListener = { _, _, _, position ->
            val action = StepFragmentDirections.actionStepFragmentToCondFragment(
                condIndex = position,
                condJson = GsonUtils.toJson(mStep.condList[position])
            )
            findNavController().navigate(action)
            false
        }
        fastAdapter.onLongClickListener = { _, _, _, position ->
            MaterialAlertDialogBuilder(requireContext())
                .setMessage("删除该条件？")
                .setNegativeButton("取消") { dialog, which ->
                    // Respond to negative button press
                    dialog.cancel()
                }
                .setPositiveButton("确定") { dialog, which ->
                    // Respond to positive button press
                    mStep.condList.removeAt(position)
                    loadData()
                }
                .show()
            false
        }

        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    fun loadData() {
        binding.stepName.editText?.setText(mStep.name)

        binding.stepAlarm.isChecked = mStep.isAlarm
        binding.stepManual.isChecked = mStep.isManual
        binding.stepRepeat.isChecked = mStep.isRepeat
        binding.stepFailBack.isChecked = mStep.isFailBack
        binding.stepFailBackCount.editText?.setText("${mStep.failBackCount}")

        var handleContent = "类型：${mStep.handle.type.toStr()}"
        handleContent += "\n节点："
        handleContent += "\n   - 类型：${mStep.handle.node.nodeType.toStr()}"
        handleContent += "\n   - 值：${mStep.handle.node.nodeKey}"
        handleContent += "\n   - 包名：${mStep.handle.node.packageName}"
        handleContent += "\n   - 类名：${mStep.handle.node.className}"
        handleContent += "\n执行前延迟(ms)：${mStep.handle.delayRunBefore}"
        handleContent += "\n执行后延迟(ms)：${mStep.handle.delayRunAfter}"
        binding.stepHandleValue.text = handleContent

        itemAdapter.clear()
        mStep.condList.forEachIndexed { index, mcStep ->
            itemAdapter.add(BindingCondItem(mcStep).apply {
                tag = index + 1
            })
        }
    }

    fun saveData() {


        val stepJson = GsonUtils.toJson(mStep)

        setFragmentResult("updateStep", bundleOf("stepJson" to stepJson, "stepIndex" to args.stepIndex))
        findNavController().navigateUp()
    }

    class BindingCondItem(model: MCCond) : ModelAbstractBindingItem<MCCond, ItemCondBinding>(model) {

        override val type: Int
            get() = R.id.adapter_cond_item

        override fun bindView(binding: ItemCondBinding, payloads: List<Any>) {
            binding.adapterCondNo.text = "条件" + tag.toString()

            var condContent = "类型：${model.type.toStr()}"
            condContent += "\n节点："
            condContent += "\n   - 类型：${model.node.nodeType.toStr()}"
            condContent += "\n   - 值：${model.node.nodeKey}"
            condContent += "\n   - 包名：${model.node.packageName}"
            condContent += "\n   - 类名：${model.node.className}"
            binding.adapterCondValue.text = condContent

        }

        override fun createBinding(inflater: LayoutInflater, parent: ViewGroup?): ItemCondBinding {
            return ItemCondBinding.inflate(inflater, parent, false)
        }
    }

}