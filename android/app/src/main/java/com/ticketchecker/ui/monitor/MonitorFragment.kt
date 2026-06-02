package com.ticketchecker.ui.monitor

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.ticketchecker.databinding.FragmentMonitorBinding
import com.ticketchecker.service.TicketCheckerService
import kotlinx.coroutines.launch

class MonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MonitorViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupObservers()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) viewModel.refresh()
    }

    private fun setupObservers() {
        viewModel.interparkTarget.observe(viewLifecycleOwner) { target ->
            if (target == null) {
                binding.tvInterparkUnset.visibility = View.VISIBLE
                binding.llInterparkInfo.visibility = View.GONE
                binding.btnClearInterpark.visibility = View.GONE
            } else {
                binding.tvInterparkUnset.visibility = View.GONE
                binding.llInterparkInfo.visibility = View.VISIBLE
                binding.btnClearInterpark.visibility = View.VISIBLE

                // 공연명
                binding.tvInterparkName.text = target.goodsName.ifEmpty { "(미입력)" }

                // 날짜 + 회차
                val dateStr = formatPlayDate(target.playDate)
                val seqNum = target.playSeq.trimStart('0').ifEmpty { target.playSeq }
                val seqStr = if (seqNum.isNotEmpty()) "${seqNum}회차" else ""
                binding.tvInterparkDate.text = when {
                    dateStr.isNotEmpty() && seqStr.isNotEmpty() -> "$dateStr · $seqStr"
                    dateStr.isNotEmpty() -> dateStr
                    seqStr.isNotEmpty() -> seqStr
                    else -> "(미입력)"
                }

                // 모니터링 등급
                binding.tvInterparkGrades.text = if (target.watchGrades.isEmpty()) {
                    "전 등급"
                } else {
                    target.watchGrades.joinToString(", ")
                }
            }
        }

        viewModel.melonTarget.observe(viewLifecycleOwner) { target ->
            if (target == null) {
                binding.tvMelonUnset.visibility = View.VISIBLE
                binding.llMelonInfo.visibility = View.GONE
                binding.btnClearMelon.visibility = View.GONE
            } else {
                binding.tvMelonUnset.visibility = View.GONE
                binding.llMelonInfo.visibility = View.VISIBLE
                binding.btnClearMelon.visibility = View.VISIBLE

                binding.tvMelonName.text = target.name.ifEmpty { "(미입력)" }
                val melonDate = formatPlayDate(target.playDate)
                binding.tvMelonProdDetail.text = melonDate.ifEmpty { "(날짜 미확인)" }

                val gradeLabel = when {
                    target.seatGradeName.isNotEmpty() -> target.seatGradeName
                    target.seatId.isNotEmpty() -> "등급 ID: ${target.seatId}"
                    else -> null
                }
                if (gradeLabel != null) {
                    binding.llMelonGrade.visibility = View.VISIBLE
                    binding.tvMelonGrade.text = gradeLabel
                } else {
                    binding.llMelonGrade.visibility = View.GONE
                }
            }
        }

        viewModel.logs.observe(viewLifecycleOwner) { text ->
            binding.tvLog.text = text
            // 새 로그 추가 시 자동으로 맨 아래로 스크롤
            binding.scrollLog.post {
                binding.scrollLog.fullScroll(View.FOCUS_DOWN)
            }
        }

        viewModel.isServiceRunning.observe(viewLifecycleOwner) { running ->
            if (running) {
                binding.tvServiceStatus.text = "실행 중"
                binding.btnStartService.isEnabled = false
                binding.btnStopService.isEnabled = true
                binding.indicatorRunning.visibility = View.VISIBLE
            } else {
                binding.tvServiceStatus.text = "정지됨"
                binding.btnStartService.isEnabled = true
                binding.btnStopService.isEnabled = false
                binding.indicatorRunning.visibility = View.INVISIBLE
            }
        }
    }

    private fun setupButtons() {
        binding.btnStartService.setOnClickListener {
            startCheckerService()
            viewModel.onServiceStartRequested()
        }

        binding.btnStopService.setOnClickListener {
            stopCheckerService()
            viewModel.onServiceStopRequested()
        }

        binding.btnClearInterpark.setOnClickListener { viewModel.clearInterparkTarget() }
        binding.btnClearMelon.setOnClickListener { viewModel.clearMelonTarget() }
        binding.btnRefresh.setOnClickListener { viewModel.refresh() }
        binding.btnClearLogs.setOnClickListener { viewModel.clearLogs() }

        // 인터파크 등급 편집 — API에서 등급 목록 받아와 체크박스로 표시
        binding.btnEditGrades.setOnClickListener {
            showGradesDialog()
        }
    }

    private fun showGradesDialog() {
        val loadingDialog = AlertDialog.Builder(requireContext())
            .setMessage("등급 목록을 불러오는 중...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val allGrades = viewModel.fetchGradeNames()
            loadingDialog.dismiss()

            if (allGrades.isEmpty()) {
                Toast.makeText(requireContext(), "등급 정보를 가져올 수 없습니다.\n세션이 만료되었을 수 있습니다.", Toast.LENGTH_LONG).show()
                return@launch
            }

            val currentGrades = viewModel.interparkTarget.value?.watchGrades ?: emptyList()
            val checked = BooleanArray(allGrades.size) { allGrades[it] in currentGrades }
            val selected = checked.clone()

            AlertDialog.Builder(requireContext())
                .setTitle("모니터링 등급 선택 (미선택 = 전 등급)")
                .setMultiChoiceItems(allGrades.toTypedArray(), checked) { _, which, isChecked ->
                    selected[which] = isChecked
                }
                .setPositiveButton("저장") { _, _ ->
                    val newGrades = allGrades.filterIndexed { idx, _ -> selected[idx] }
                    viewModel.updateInterparkWatchGrades(newGrades)
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun formatPlayDate(playDate: String): String {
        if (playDate.length != 8) return playDate
        return "${playDate.substring(0, 4)}.${playDate.substring(4, 6)}.${playDate.substring(6, 8)}"
    }

    private fun startCheckerService() {
        val intent = Intent(requireContext(), TicketCheckerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }

    private fun stopCheckerService() {
        val intent = Intent(requireContext(), TicketCheckerService::class.java).apply {
            action = TicketCheckerService.ACTION_STOP
        }
        requireContext().startService(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
