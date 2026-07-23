function smartPileSetup(options = {}) {
    const { ref, onMounted, computed, onUnmounted, watch, nextTick } = Vue;
    const isClientPage = options.page === 'client';
    const pollIntervalMs = options.pollIntervalMs ?? (isClientPage ? 500 : 3000);
                const simTime = ref('06:00:00');
                const simTimeSec = ref(21600);
                
                const piles = ref([]);
                const waitingArea = ref([]);
                const faultQueue = ref([]);
                const pileQueues = ref({});
                const vehicles = ref([]);
                const bills = ref([]);
                const details = ref([]);
                const events = ref([]);
                const vehicleProgress = ref({});
                const eventMode = ref('builtin');
                const modeChangeAllowed = ref(false);
                const currentUser = ref(null);
                const accountVehicles = ref([]);
                const authMode = ref('login');
                const authUsername = ref('');
                const authPassword = ref('');
                const vehicleProfile = ref({ vehicleId: '', vehicleType: '家用新能源车', batteryCapacity: 60, currentCapacity: 10 });

                // Form DTO States
                const formMode = ref('FAST');
                const formAmount = ref(20);
                
                const changeAmount = ref(15);
                const selectedVehicleId = ref('V1');

                // Simulation autoplay controller
                const isAutoPlaying = ref(false);
                const isSimBusy = ref(false);
                let autoPlayTimer = null;
                const simLogs = ref([]);
                const dailyReport = ref({ date: '', rows: [] });
                const reportPeriod = ref('daily');
                const businessDataCounts = ref({ queueRecords: 0, rechargeRecords: 0, details: 0, bills: 0 });
                const adminDetailVehicleId = ref('all');
                const exportType = ref('details');
                const exportScope = ref('all');
                const exportVehicleId = ref('V1');
                const isExporting = ref(false);

                // 自动播放期间 isSimBusy 会在每次 tick 间隙短暂为 false；用合并状态驱动 UI，避免标识闪烁
                const showSimRunning = computed(() => isAutoPlaying.value || isSimBusy.value);

                const stopAutoPlay = () => {
                    isAutoPlaying.value = false;
                    if (autoPlayTimer !== null) {
                        clearTimeout(autoPlayTimer);
                        autoPlayTimer = null;
                    }
                };

                const refreshLucideIcons = (rootEl) => {
                    if (!window.lucide || !rootEl) return;
                    lucide.createIcons({ root: rootEl });
                };

                const fetchDailyReport = async () => {
                    try {
                        const response = await fetch(`/api/reports/summary?period=${reportPeriod.value}`);
                        if (response.ok) {
                            const data = await response.json();
                            dailyReport.value = { ...data, date: data.label };
                        }
                    } catch (error) {
                        console.error("Error fetching daily report:", error);
                    }
                };

                const fetchAccount = async () => {
                    try {
                        const response = await fetch('/api/auth/me');
                        if (!response.ok) return;
                        const data = await response.json();
                        currentUser.value = data.authenticated ? data.user : null;
                        if (currentUser.value) {
                            await fetchAccountVehicles();
                        } else {
                            accountVehicles.value = [];
                        }
                    } catch (error) {
                        console.error('Error fetching account:', error);
                    }
                };

                const fetchAccountVehicles = async () => {
                    const response = await fetch('/api/account/vehicles');
                    if (response.ok) {
                        accountVehicles.value = await response.json();
                        if (isClientPage) {
                            if (accountVehicles.value.length === 0) {
                                selectedVehicleId.value = '';
                            } else if (!accountVehicles.value.some(v => v.id === selectedVehicleId.value)) {
                                selectedVehicleId.value = accountVehicles.value[0].id;
                            }
                        }
                    }
                };

                const submitAuth = async () => {
                    if (!authUsername.value.trim() || authPassword.value.length < 6) {
                        alert('请输入用户名和至少 6 位密码。');
                        return;
                    }
                    const response = await fetch(`/api/auth/${authMode.value}`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ username: authUsername.value.trim(), password: authPassword.value })
                    });
                    const data = await response.json();
                    if (!response.ok) {
                        alert(data.message || '认证失败');
                        return;
                    }
                    currentUser.value = data.user;
                    authPassword.value = '';
                    await fetchAccountVehicles();
                };

                const logout = async () => {
                    await fetch('/api/auth/logout', { method: 'POST' });
                    currentUser.value = null;
                    accountVehicles.value = [];
                    selectedVehicleId.value = 'V1';
                };

                const addVehicleProfile = async () => {
                    const response = await fetch('/api/account/vehicles', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(vehicleProfile.value)
                    });
                    const data = await response.json();
                    if (!response.ok) {
                        alert(data.message || '车辆档案保存失败');
                        return;
                    }
                    vehicleProfile.value.vehicleId = '';
                    await Promise.all([fetchAccountVehicles(), fetchData()]);
                    selectedVehicleId.value = data.id;
                };

                const setReportPeriod = async (period) => {
                    reportPeriod.value = period;
                    await fetchDailyReport();
                };

                // Load initial data
                const fetchData = async () => {
                    try {
                        const response = await fetch('/api/state');
                        if (response.ok) {
                            const data = await response.json();
                            simTime.value = data.time;
                            simTimeSec.value = data.timeSec;
                            piles.value = data.piles;
                            waitingArea.value = data.waitingArea;
                            faultQueue.value = data.faultQueue;
                            pileQueues.value = data.pileQueues;
                            vehicles.value = data.vehicles;
                            bills.value = data.bills;
                            details.value = data.details;
                            events.value = data.events;
                            vehicleProgress.value = data.vehicleProgress || {};
                            eventMode.value = data.eventMode || 'builtin';
                            modeChangeAllowed.value = !!data.modeChangeAllowed;
                            businessDataCounts.value = data.businessDataCounts || { queueRecords: 0, rechargeRecords: 0, details: 0, bills: 0 };
                            await fetchDailyReport();
                        }
                    } catch (error) {
                        console.error("Error fetching state:", error);
                    }
                };

                const addLog = (msg) => {
                    simLogs.value.unshift(`[${simTime.value}] ${msg}`);
                    if (simLogs.value.length > 50) {
                        simLogs.value.pop();
                    }
                };

                // Time Formatting Helper
                const formatTimeFromDateTime = (dtStr) => {
                    if (!dtStr) return '';
                    if (dtStr.includes('T')) {
                        const timePart = dtStr.split('T')[1];
                        const parts = timePart.split(':');
                        const h = parts[0] || '00';
                        const m = parts[1] || '00';
                        const s = parts[2] ? parts[2].substring(0, 2) : '00';
                        return `${h.padStart(2, '0')}:${m.padStart(2, '0')}:${s.padStart(2, '0')}`;
                    }
                    return dtStr;
                };

                // Normalization helper to remove trailing zeros in UI
                const formatNum = (val) => {
                    if (val === undefined || val === null) return '0';
                    let s = parseFloat(val).toFixed(2);
                    if (s.includes('.')) {
                        s = s.replace(/0+$/, '');
                        if (s.endsWith('.')) {
                            s = s.substring(0, s.length - 1);
                        }
                    }
                    return s;
                };

                // Interactive controls
                const resetSim = async () => {
                    if (isSimBusy.value) {
                        stopAutoPlay();
                        await new Promise(r => setTimeout(r, 200));
                        if (isSimBusy.value) return;
                    }
                    stopAutoPlay();
                    isSimBusy.value = true;
                    try {
                        const res = await fetch('/api/sim/reset', { method: 'POST' });
                        if (res.ok) {
                            const data = await res.json();
                            simLogs.value = [];
                            eventMode.value = data.eventMode || 'builtin';
                            modeChangeAllowed.value = !!data.modeChangeAllowed;
                            addLog("系统重置成功，业务数据已清空。06:00:00 首个用例将在首次推进时执行。");
                            await fetchData();
                        }
                    } catch (error) {
                        console.error(error);
                    } finally {
                        isSimBusy.value = false;
                    }
                };

                const setEventMode = async (mode) => {
                    if (!modeChangeAllowed.value || isSimBusy.value || eventMode.value === mode) return;
                    isSimBusy.value = true;
                    try {
                        const res = await fetch(`/api/sim/mode?mode=${mode}`, { method: 'POST' });
                        const data = await res.json();
                        if (res.ok && data.status === 'SUCCESS') {
                            eventMode.value = data.eventMode;
                            modeChangeAllowed.value = !!data.modeChangeAllowed;
                            if (mode === 'builtin') {
                                addLog("已切换为「内置用例」：挂载 32 个官方验收用例，首个事件将在开始推进时执行。");
                            } else {
                                addLog("已切换为「清除用例」：自由演示模式，无内置排程，可手动提交充电申请。");
                            }
                            await fetchData();
                        } else {
                            alert(data.message || '模式切换失败');
                        }
                    } catch (error) {
                        console.error(error);
                    } finally {
                        isSimBusy.value = false;
                    }
                };

                const tickSim = async (seconds) => {
                    if (isSimBusy.value) return;
                    isSimBusy.value = true;
                    try {
                        const url = `/api/sim/tick?seconds=${seconds}`;
                        const res = await fetch(url, { method: 'POST' });
                        if (res.ok) {
                            await fetchData();
                            detectEventsAndStatus();
                        }
                    } catch (error) {
                        console.error(error);
                    } finally {
                        isSimBusy.value = false;
                    }
                };

                const nextEventSim = async () => {
                    if (isSimBusy.value) return;
                    isSimBusy.value = true;
                    try {
                        const res = await fetch('/api/sim/next-event', { method: 'POST' });
                        if (res.ok) {
                            await fetchData();
                            addLog("跳跃式推进到下一个事件执行点。");
                            detectEventsAndStatus();
                        }
                    } catch (error) {
                        console.error(error);
                    } finally {
                        isSimBusy.value = false;
                    }
                };

                const scheduleAutoPlayTick = () => {
                    if (!isAutoPlaying.value) return;
                    if (isSimBusy.value) {
                        autoPlayTimer = setTimeout(scheduleAutoPlayTick, 80);
                        return;
                    }
                    tickSim(5).finally(() => {
                        if (isAutoPlaying.value) {
                            autoPlayTimer = setTimeout(scheduleAutoPlayTick, 120);
                        }
                    });
                };

                const toggleAutoPlay = () => {
                    if (isAutoPlaying.value) {
                        stopAutoPlay();
                        addLog("自动播放已暂停。");
                        return;
                    }
                    isAutoPlaying.value = true;
                    addLog("自动播放开启：系统以 300x 速度进行离散事件时钟级演进。");
                    scheduleAutoPlayTick();
                };

                // Custom log triggers on state shifts
                let priorActiveCount = 0;
                const detectEventsAndStatus = () => {
                    // Check if newly bills are present
                    const activeWaitCount = waitingArea.value.length;
                    const activeFaultCount = faultQueue.value.length;
                };

                // Piles Operations
                const breakdownPile = async (pileId) => {
                    try {
                        const res = await fetch(`/api/pile/breakdown?pileId=${pileId}`, { method: 'POST' });
                        if (res.ok) {
                            addLog(`⚠️ 发生断电损坏事件！触发重调度策略 A 抢修。桩「${pileId}」已转为 FAULT！`);
                            await fetchData();
                        }
                    } catch (error) {
                        console.error(error);
                    }
                };

                const recoverPile = async (pileId) => {
                    try {
                        const res = await fetch(`/api/pile/recover?pileId=${pileId}`, { method: 'POST' });
                        if (res.ok) {
                            addLog(`⚡ 充电桩恢复！桩「${pileId}」物理修毕重新拉起。触发对应调度解冻。`);
                            await fetchData();
                        }
                    } catch (error) {
                        console.error(error);
                    }
                };

                const stopPile = async (pileId) => {
                    try {
                        const res = await fetch(`/api/pile/stop?pileId=${pileId}`, { method: 'POST' });
                        const data = await res.json();
                        if (res.ok && data.status === 'SUCCESS') {
                            addLog(`🔌 管理员关闭充电桩「${pileId}」，桩上车辆已退回主等候区。`);
                            await fetchData();
                        } else {
                            alert(data.message || '关闭充电桩失败');
                        }
                    } catch (error) {
                        console.error(error);
                    }
                };

                const startPile = async (pileId) => {
                    try {
                        const res = await fetch(`/api/pile/start?pileId=${pileId}`, { method: 'POST' });
                        const data = await res.json();
                        if (res.ok && data.status === 'SUCCESS') {
                            addLog(`✅ 充电桩「${pileId}」已重新启动，恢复接单。`);
                            await fetchData();
                        } else {
                            alert(data.message || '启动充电桩失败');
                        }
                    } catch (error) {
                        console.error(error);
                    }
                };

                const getPileStateLabel = (state) => {
                    if (state === 'RUNNING') return '正常';
                    if (state === 'FAULT') return '故障';
                    if (state === 'OFFLINE') return '已关闭';
                    return state || '未知';
                };

                const getPileStateBadgeClass = (state) => {
                    if (state === 'RUNNING') return 'bg-emerald-100 text-emerald-800';
                    if (state === 'FAULT') return 'bg-red-500 text-white';
                    if (state === 'OFFLINE') return 'bg-slate-400 text-white';
                    return 'bg-slate-100 text-slate-600';
                };

                // Client Operations
                const submitChargeRequest = async () => {
                    if (!selectedVehicleId.value) {
                        alert("请先在左侧选择车主身份！");
                        return;
                    }
                    if (selectedVehicleHasActiveOrder.value) {
                        alert(`车辆 ${selectedVehicleId.value} 已有进行中的充电订单，请在「变更与退单中心」修改或取消。`);
                        return;
                    }
                    if (!formAmount.value || formAmount.value <= 0) {
                        alert("请输入有效的充电度数！");
                        return;
                    }
                    const profile = accountVehicles.value.find(v => v.id === selectedVehicleId.value);
                    try {
                        const response = await fetch('/api/charge/request', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({
                                vehicleId: selectedVehicleId.value,
                                vehicleType: profile?.vehicleType || "EV-Model",
                                batteryCapacity: profile?.batteryCapacity || 60.0,
                                currentCapacity: profile?.currentCapacity || 10.0,
                                mode: formMode.value,
                                requestedAmount: parseFloat(formAmount.value)
                            })
                        });
                        const data = await response.json();
                        if (response.ok && data.status === 'SUCCESS') {
                            addLog(`车辆 ${selectedVehicleId.value} 提交充电申请：${formMode.value === 'FAST' ? '快充' : '慢充'} ${formAmount.value}度。`);
                            await fetchData();
                        } else {
                            alert(data.message || '提交失败，该车辆可能已有进行中的订单。');
                        }
                    } catch (error) {
                        console.error(error);
                    }
                };

                const cancelSelectedVehicle = async () => {
                    try {
                        const res = await fetch(`/api/charge/cancel?vehicleId=${selectedVehicleId.value}`, { method: 'POST' });
                        if (res.ok) {
                            addLog(`车主主动申请退款取消充电。车辆 ${selectedVehicleId.value} 成功出局。`);
                            await fetchData();
                        }
                    } catch (error) {
                        console.error(error);
                    }
                };

                const submitChangeAmount = async () => {
                    if (!changeAmount.value || changeAmount.value <= 0) {
                        alert("请输入有效的目标度数！");
                        return;
                    }
                    const chargedSoFar = getVehicleChargedQ(selectedVehicleId.value);
                    if (changeAmount.value < chargedSoFar - 1e-9) {
                        alert(`修改度数不能小于已充电量（当前已充 ${formatNum(chargedSoFar)} 度）`);
                        return;
                    }
                    try {
                        const response = await fetch('/api/charge/change', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({
                                vehicleId: selectedVehicleId.value,
                                amount: parseFloat(changeAmount.value)
                            })
                        });
                        const data = await response.json();
                        if (response.ok && data.status === 'SUCCESS') {
                            addLog(`车辆 ${selectedVehicleId.value} 修改所需总度数为 ${changeAmount.value}度。`);
                            await fetchData();
                        } else {
                            alert(data.message || '修改失败');
                        }
                    } catch (error) {
                        console.error(error);
                    }
                };

                const submitChangeMode = async (mode) => {
                    if (!selectedVehicle.value || selectedVehicle.value.state !== 'WAITING_IN_AREA') {
                        alert('仅等候区车辆可变更充电类型。');
                        return;
                    }
                    if (selectedVehicle.value.chargeMode === mode) {
                        alert('当前已是该充电类型。');
                        return;
                    }
                    const payload = {
                        vehicleId: selectedVehicleId.value,
                        mode: mode
                    };
                    if (changeAmount.value && changeAmount.value > 0) {
                        const chargedSoFar = getVehicleChargedQ(selectedVehicleId.value);
                        if (changeAmount.value < chargedSoFar - 1e-9) {
                            alert(`修改度数不能小于已充电量（当前已充 ${formatNum(chargedSoFar)} 度）`);
                            return;
                        }
                        payload.amount = parseFloat(changeAmount.value);
                    }
                    try {
                        const response = await fetch('/api/charge/change', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify(payload)
                        });
                        const data = await response.json();
                        if (response.ok && data.status === 'SUCCESS') {
                            const modeLabel = mode === 'FAST' ? '快充' : '慢充';
                            const amountPart = payload.amount ? `，目标度数 ${payload.amount} 度` : '，充电量不变';
                            addLog(`车辆 ${selectedVehicleId.value} 在等候区变更充电类型为 ${modeLabel}${amountPart}，已重新排队。`);
                            await fetchData();
                        } else {
                            alert(data.message || '变更充电类型失败');
                        }
                    } catch (error) {
                        console.error(error);
                    }
                };

                const selectClientVehicle = (carId) => {
                    selectedVehicleId.value = carId;
                };

                // Computeds & Selectors Helpers
                const vehicleIds = computed(() => {
                    if (currentUser.value) {
                        return accountVehicles.value.map(v => v.id);
                    }
                    const ids = new Set();
                    // Populate from V1 to V22 default
                    for (let i = 1; i <= 22; i++) {
                        ids.add(`V${i}`);
                    }
                    // Also merge from currently registered vehicles in db
                    vehicles.value.forEach(v => ids.add(v.id));
                    return Array.from(ids).sort((a,b) => {
                        try {
                            return parseInt(a.substring(1)) - parseInt(b.substring(1));
                        } catch (e) {
                            return a.localeCompare(b);
                        }
                    });
                });

                const selectedVehicle = computed(() => {
                    if (currentUser.value && !accountVehicles.value.some(v => v.id === selectedVehicleId.value)) {
                        return undefined;
                    }
                    return vehicles.value.find(v => v.id === selectedVehicleId.value);
                });

                const selectedVehicleHasActiveOrder = computed(() => {
                    if (!selectedVehicle.value) return false;
                    const state = selectedVehicle.value.state;
                    return state === 'WAITING_IN_AREA' || state === 'QUEUING_IN_PILE' || state === 'CHARGING';
                });

                const selectedVehicleDetails = computed(() => {
                    return details.value.filter(d => d.vehicle.id === selectedVehicleId.value);
                });

                const selectedVehicleBill = computed(() => {
                    const vehicleDetails = selectedVehicleDetails.value;
                    if (vehicleDetails.length === 0) return null;

                    const sumField = (field) => vehicleDetails.reduce((acc, d) => acc + (d[field] || 0), 0);

                    const vehicleBills = bills.value.filter(b => {
                        const vid = b.vehicle?.id;
                        return vid === selectedVehicleId.value;
                    });
                    const activeBill = vehicleBills.find(b => !b.isPaid) || vehicleBills[0];

                    return {
                        billNo: activeBill ? activeBill.billNo : '--',
                        chargedAmount: sumField('chargedAmount'),
                        chargeCost: sumField('chargeCost'),
                        serviceCost: sumField('serviceCost'),
                        totalCost: sumField('totalCost'),
                    };
                });

                const allKnownVehicleIds = computed(() => {
                    const ids = new Set();
                    for (let i = 1; i <= 22; i++) ids.add(`V${i}`);
                    vehicles.value.forEach(v => ids.add(v.id));
                    details.value.forEach(d => ids.add(d.vehicle.id));
                    bills.value.forEach(b => {
                        if (b.vehicle?.id) ids.add(b.vehicle.id);
                    });
                    return Array.from(ids).sort((a, b) => {
                        const av = /^V(\d+)$/.exec(a);
                        const bv = /^V(\d+)$/.exec(b);
                        if (av && bv) return parseInt(av[1]) - parseInt(bv[1]);
                        return a.localeCompare(b, 'zh-CN');
                    });
                });

                const businessDataCleared = computed(() => {
                    const counts = businessDataCounts.value;
                    return counts.queueRecords === 0
                        && counts.rechargeRecords === 0
                        && counts.details === 0
                        && counts.bills === 0;
                });

                const adminFilteredDetails = computed(() => {
                    if (adminDetailVehicleId.value === 'all') return details.value;
                    return details.value.filter(d => d.vehicle.id === adminDetailVehicleId.value);
                });

                const adminDetailSummary = computed(() => {
                    const rows = adminFilteredDetails.value;
                    const sum = field => rows.reduce((total, row) => total + (row[field] || 0), 0);
                    const selectedBills = adminDetailVehicleId.value === 'all'
                        ? bills.value
                        : bills.value.filter(b => b.vehicle?.id === adminDetailVehicleId.value);
                    return {
                        label: adminDetailVehicleId.value === 'all' ? '全部车辆' : adminDetailVehicleId.value,
                        billNo: adminDetailVehicleId.value === 'all' ? `${selectedBills.length} 张账单` : (selectedBills[0]?.billNo || '--'),
                        detailCount: rows.length,
                        chargedAmount: sum('chargedAmount'),
                        chargeCost: sum('chargeCost'),
                        serviceCost: sum('serviceCost'),
                        totalCost: sum('totalCost')
                    };
                });

                const exportCsv = async () => {
                    if (isExporting.value) return;
                    if (exportScope.value === 'vehicle' && !exportVehicleId.value) {
                        alert('请选择要导出的车辆。');
                        return;
                    }
                    isExporting.value = true;
                    try {
                        const params = new URLSearchParams({
                            type: exportType.value,
                            scope: exportScope.value
                        });
                        if (exportScope.value === 'vehicle') {
                            params.set('vehicleId', exportVehicleId.value);
                        }
                        const response = await fetch(`/api/exports/csv?${params.toString()}`);
                        if (!response.ok) {
                            throw new Error(await response.text());
                        }
                        const blob = await response.blob();
                        const disposition = response.headers.get('Content-Disposition') || '';
                        const utf8Name = disposition.match(/filename\*=UTF-8''([^;]+)/i);
                        const plainName = disposition.match(/filename="?([^";]+)"?/i);
                        let filename = `G12_${exportType.value === 'details' ? '车辆详单' : '车辆账单'}.csv`;
                        if (utf8Name) filename = decodeURIComponent(utf8Name[1]);
                        else if (plainName) filename = plainName[1];

                        const url = URL.createObjectURL(blob);
                        const link = document.createElement('a');
                        link.href = url;
                        link.download = filename;
                        document.body.appendChild(link);
                        link.click();
                        link.remove();
                        URL.revokeObjectURL(url);
                        addLog(`已导出${exportScope.value === 'all' ? '全部车辆' : exportVehicleId.value}的${exportType.value === 'details' ? '详单' : '账单'} CSV。`);
                    } catch (error) {
                        console.error(error);
                        alert('导出失败，请检查后端服务。');
                    } finally {
                        isExporting.value = false;
                    }
                };

                // UI Helpers for States
                const selectedVehicleStateName = computed(() => {
                    if (!selectedVehicle.value) return '未激活';
                    const state = selectedVehicle.value.state;
                    if (state === 'WAITING_IN_AREA') return '等候区排队';
                    if (state === 'QUEUING_IN_PILE') return '电桩前排队';
                    if (state === 'CHARGING') return '正在充电中';
                    if (state === 'FINISHED') return '充电完成';
                    if (state === 'CANCELLED') return '已取消退单';
                    return '未知';
                });

                const selectedVehicleStateClass = computed(() => {
                    if (!selectedVehicle.value) return 'bg-slate-100 text-slate-500';
                    const state = selectedVehicle.value.state;
                    if (state === 'WAITING_IN_AREA') return 'bg-indigo-100 text-indigo-800 border border-indigo-200';
                    if (state === 'QUEUING_IN_PILE') return 'bg-amber-100 text-amber-800 border border-amber-200';
                    if (state === 'CHARGING') return 'bg-emerald-100 text-emerald-800 border border-emerald-200 animate-pulse';
                    if (state === 'FINISHED') return 'bg-blue-100 text-blue-800 border border-blue-200';
                    if (state === 'CANCELLED') return 'bg-slate-100 text-slate-500 border border-slate-200';
                    return 'bg-slate-100 text-slate-500';
                });

                const selectedVehicleModeClass = computed(() => {
                    if (!selectedVehicle.value) return 'bg-slate-400';
                    return selectedVehicle.value.chargeMode === 'FAST' ? 'bg-amber-500 shadow-amber-500/20' : 'bg-emerald-500 shadow-emerald-500/20';
                });

                const selectedVehicleIcon = computed(() => {
                    if (!selectedVehicle.value) return 'help-circle';
                    return selectedVehicle.value.chargeMode === 'FAST' ? 'zap' : 'leaf';
                });

                const getChargeModeLabel = (mode) => {
                    if (mode === 'FAST') return '快充';
                    if (mode === 'TRICKLE') return '慢充';
                    return '未知';
                };

                /** 等候区内同充电模式下的排队位次（1-based），按全局入区顺序筛选 */
                const getWaitingAreaModePosition = (vehicleId) => {
                    const vehicle = vehicles.value.find(v => v.id === vehicleId);
                    if (!vehicle || vehicle.state !== 'WAITING_IN_AREA') return 0;
                    const sameModeQueue = waitingArea.value
                        .filter(car => car.chargeMode === vehicle.chargeMode)
                        .sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
                    const idx = sameModeQueue.findIndex(car => car.vehicleId === vehicleId);
                    return idx >= 0 ? idx + 1 : 0;
                };

                const getWaitingAreaModeCount = (mode) => {
                    return waitingArea.value.filter(car => car.chargeMode === mode).length;
                };

                const getSelectedPileQueueRecord = () => {
                    for (const queue of Object.values(pileQueues.value || {})) {
                        const record = queue.find(item => item.vehicleId === selectedVehicleId.value);
                        if (record) return record;
                    }
                    return null;
                };

                const getPileAheadCount = () => getSelectedPileQueueRecord()?.aheadCount || 0;

                const getPileQueueSlots = (pileId) => {
                    const list = pileQueues.value[pileId] || [];
                    const slots = [null, null, null];
                    for (let i = 0; i < Math.min(list.length, 3); i++) {
                        slots[i] = list[i];
                    }
                    return slots;
                };

                // 进度与费用：严格读取后端 /api/state 中的 vehicleProgress，不做前端估算
                const getVehicleChargedQ = (vehicleId) => {
                    const p = vehicleProgress.value[vehicleId];
                    if (p && p.chargedAmount != null) return p.chargedAmount;
                    return details.value
                        .filter(d => d.vehicle.id === vehicleId)
                        .reduce((sum, d) => sum + d.chargedAmount, 0);
                };

                const getVehicleCurrentCost = (vehicleId) => {
                    const p = vehicleProgress.value[vehicleId];
                    if (p && p.totalCost != null) return p.totalCost;
                    return details.value
                        .filter(d => d.vehicle.id === vehicleId)
                        .reduce((sum, d) => sum + d.totalCost, 0);
                };

                // Dynamic Pricing period style
                const pricePeriodName = computed(() => {
                    const hours = simTimeSec.value / 3600.0;
                    if ((10.0 <= hours && hours < 15.0) || (18.0 <= hours && hours < 21.0)) return '峰时段 🔴';
                    if ((7.0 <= hours && hours < 10.0) || (15.0 <= hours && hours < 18.0) || (21.0 <= hours && hours < 23.0)) return '平时段 🟡';
                    return '谷时段 🟢';
                });

                const currentPrice = computed(() => {
                    const hours = simTimeSec.value / 3600.0;
                    if ((10.0 <= hours && hours < 15.0) || (18.0 <= hours && hours < 21.0)) return '1.0';
                    if ((7.0 <= hours && hours < 10.0) || (15.0 <= hours && hours < 18.0) || (21.0 <= hours && hours < 23.0)) return '0.7';
                    return '0.4';
                });

                const priceStyleClass = computed(() => {
                    const name = pricePeriodName.value;
                    if (name.includes('峰')) return 'bg-red-50 text-red-700 border border-red-200';
                    if (name.includes('平')) return 'bg-amber-50 text-amber-700 border border-amber-200';
                    return 'bg-emerald-50 text-emerald-700 border border-emerald-200';
                });

                const getEventTimelineClass = (evt) => {
                    if (evt.executed) return 'bg-slate-200 text-slate-500 border border-slate-300';
                    if (evt.timeSec < simTimeSec.value) return 'bg-red-50 text-red-500 border border-red-200';
                    if (evt.timeSec === simTimeSec.value) return 'bg-amber-500 text-white shadow-md scale-105';
                    return 'bg-white text-slate-700 border border-slate-200';
                };

                const getEventIcon = (evt) => {
                    if (evt.executed) return 'check';
                    if (evt.type === 'A') return 'plus-circle';
                    if (evt.type === 'C') return 'sliders';
                    return 'alert-triangle';
                };

                const getLogColorClass = (log) => {
                    if (log.includes('损坏') || log.includes('⚠️')) return 'text-red-400 font-bold';
                    if (log.includes('恢复') || log.includes('修毕')) return 'text-emerald-400 font-bold';
                    if (log.includes('完成') || log.includes('Finished')) return 'text-blue-400 font-medium';
                    return 'text-emerald-400';
                };

                watch(events, () => {
                    nextTick(() => refreshLucideIcons(document.getElementById('event-timeline')));
                }, { deep: true });

                onMounted(() => {
                    Promise.all([fetchData(), fetchAccount()]).then(() => {
                        nextTick(() => {
                            const appRoot = document.getElementById('app');
                            refreshLucideIcons(appRoot);
                        });
                    });

                    // 管理员自动播放时由 tickSim 内 fetchData 驱动刷新；客户端仅被动轮询，需更高频率跟上仿真
                    const interval = setInterval(() => {
                        if (!isClientPage && (isSimBusy.value || isAutoPlaying.value)) return;
                        fetchData();
                    }, pollIntervalMs);

                    onUnmounted(() => {
                        clearInterval(interval);
                        stopAutoPlay();
                    });
                });

    return {
                    simTime,
                    simTimeSec,
                    piles,
                    waitingArea,
                    faultQueue,
                    pileQueues,
                    vehicles,
                    bills,
                    details,
                    events,
                    vehicleProgress,
                    eventMode,
                    modeChangeAllowed,
                    formMode,
                    formAmount,
                    changeAmount,
                    selectedVehicleId,
                    isAutoPlaying,
                    isSimBusy,
                    showSimRunning,
                    simLogs,
                    resetSim,
                    setEventMode,
                    tickSim,
                    nextEventSim,
                    toggleAutoPlay,
                    dailyReport,
                    reportPeriod,
                    setReportPeriod,
                    businessDataCounts,
                    businessDataCleared,
                    adminDetailVehicleId,
                    adminFilteredDetails,
                    adminDetailSummary,
                    allKnownVehicleIds,
                    exportType,
                    exportScope,
                    exportVehicleId,
                    isExporting,
                    exportCsv,
                    currentUser,
                    accountVehicles,
                    authMode,
                    authUsername,
                    authPassword,
                    vehicleProfile,
                    submitAuth,
                    logout,
                    addVehicleProfile,
                    breakdownPile,
                    recoverPile,
                    stopPile,
                    startPile,
                    getPileStateLabel,
                    getPileStateBadgeClass,
                    submitChargeRequest,
                    cancelSelectedVehicle,
                    submitChangeAmount,
                    submitChangeMode,
                    selectClientVehicle,
                    vehicleIds,
                    selectedVehicle,
                    selectedVehicleHasActiveOrder,
                    selectedVehicleDetails,
                    selectedVehicleBill,
                    selectedVehicleStateName,
                    selectedVehicleStateClass,
                    selectedVehicleModeClass,
                    selectedVehicleIcon,
                    getChargeModeLabel,
                    getWaitingAreaModePosition,
                    getWaitingAreaModeCount,
                    getPileAheadCount,
                    getPileQueueSlots,
                    getVehicleChargedQ,
                    getVehicleCurrentCost,
                    pricePeriodName,
                    currentPrice,
                    priceStyleClass,
                    getEventTimelineClass,
                    getEventIcon,
                    getLogColorClass,
                    formatTimeFromDateTime,
                    formatNum
    };
}
