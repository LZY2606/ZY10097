"use strict";

const state = {
    logs: [],
    sessions: [],
    selectedLogId: null,
    selectedSession: null,
    analysis: null,
    hypothesis: emptyHypothesis(),
    conflict: null,
};

function emptyHypothesis() {
    return {ignoreEventUids: [], placeholders: [], remappedKeys: [], notes: ""};
}

async function api(path, options = {}) {
    const response = await fetch(path, {
        headers: {"Content-Type": "application/json"},
        ...options,
    });
    const text = await response.text();
    const body = text ? JSON.parse(text) : null;
    if (!response.ok) {
        const error = new Error(body && body.message ? body.message : response.statusText);
        error.status = response.status;
        error.body = body;
        throw error;
    }
    return body;
}

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (ch) => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    }[ch]));
}

function short(fp) {
    return fp ? fp.slice(0, 10) + "…" : "";
}

async function loadAll() {
    state.logs = await api("/api/logs");
    state.sessions = await api("/api/sessions");
    renderLogs();
    renderSessions();
}

function renderLogs() {
    const container = document.getElementById("log-list");
    if (!state.logs.length) {
        container.innerHTML = '<div class="hint">还没有日志。点击右上角载入样例故障。</div>';
        return;
    }
    container.innerHTML = state.logs.map((log) => `
        <div class="item ${state.selectedLogId === log.id ? "active" : ""}" data-log="${log.id}">
            <div class="name">#${log.id} ${escapeHtml(log.name)}</div>
            <div class="meta">${log.eventCount} 事件 · fp ${short(log.fingerprint)}</div>
        </div>`).join("");
    container.querySelectorAll("[data-log]").forEach((el) => {
        el.onclick = () => selectLog(Number(el.dataset.log));
    });
}

function renderSessions() {
    const container = document.getElementById("session-list");
    if (!state.sessions.length) {
        container.innerHTML = '<div class="hint">尚无会话。</div>';
        return;
    }
    container.innerHTML = state.sessions.map((session) => `
        <div class="item ${state.selectedSession && state.selectedSession.id === session.id ? "active" : ""}"
             data-session="${session.id}">
            <div class="name">${escapeHtml(session.name)} <span class="tag info">rev ${session.revision}</span>
                ${session.invalidated ? '<span class="tag warn">已失效</span>' : ""}</div>
            <div class="meta">log #${session.logId} · ${escapeHtml(session.ruleVersion)} · fp ${short(session.logFingerprint)}</div>
        </div>`).join("");
    container.querySelectorAll("[data-session]").forEach((el) => {
        el.onclick = () => selectSession(Number(el.dataset.session));
    });
}

async function selectLog(logId) {
    state.selectedLogId = logId;
    renderLogs();
    await runReplay({sessionId: null, logId});
}

async function selectSession(sessionId) {
    const session = await api(`/api/sessions/${sessionId}`);
    state.selectedSession = session;
    state.selectedLogId = session.logId;
    state.hypothesis = session.hypothesis || emptyHypothesis();
    document.getElementById("conflict-banner").classList.add("hidden");
    renderLogs();
    renderSessions();
    renderSessionMeta();
    await refreshSessionAnalysis();
}

function renderSessionMeta() {
    const session = state.selectedSession;
    const meta = document.getElementById("session-meta");
    if (!session) {
        meta.classList.add("hidden");
        return;
    }
    meta.classList.remove("hidden");
    document.getElementById("session-name").value = session.name;
    document.getElementById("rule-version").textContent = session.ruleVersion;
    document.getElementById("log-fp").textContent = session.logFingerprint;
    const rev = document.getElementById("session-rev");
    rev.textContent = "rev " + session.revision;
    rev.classList.toggle("tag", true);
}

async function refreshSessionAnalysis() {
    if (!state.selectedSession) {
        return;
    }
    await runReplay({sessionId: state.selectedSession.id});
}

async function runReplay(baseRequest) {
    const fixedText = (document.getElementById("fixed-order")?.value || "").trim();
    const uiFixedOrder = fixedText
        ? fixedText.split(",").map((item) => item.trim()).filter(Boolean)
        : null;
    const request = {
        ...baseRequest,
        hypothesis: state.hypothesis,
        fixedOrder: baseRequest.fixedOrder !== undefined ? baseRequest.fixedOrder : uiFixedOrder,
        bound: Number(document.getElementById("bound").value || 32),
    };
    try {
        state.analysis = await api("/api/replay", {
            method: "POST",
            body: JSON.stringify(request),
        });
        renderAnalysis();
    } catch (error) {
        alert("重放失败：" + error.message);
    }
}

function renderAnalysis() {
    const analysis = state.analysis;
    if (!analysis) return;
    renderGraphs(analysis);
    renderFailure(analysis);
    renderAggregates(analysis);
    renderHypothesisEditor();
    renderInterleavings(analysis);
    renderPlaceholderBanner(analysis);
}

function renderPlaceholderBanner(analysis) {
    const banner = document.getElementById("placeholder-banner");
    const open = analysis.placeholderParents || [];
    const settled = analysis.settledPlaceholderParents || [];
    if (!open.length && !settled.length) {
        banner.classList.add("hidden");
        return;
    }
    banner.classList.remove("hidden");
    banner.innerHTML = (open.length
        ? `占位父项（非业务事件，仅占位排序）：<b>${open.map(escapeHtml).join(", ")}</b>。结论为暂定。<br>`
        : "") + (settled.length
        ? `真实父项已到达，旧占位派生切片已失效，必须重算：<b>${settled.map(escapeHtml).join(", ")}</b>`
        : "");
}

function renderGraphs(analysis) {
    const area = document.getElementById("graph-area");
    if (!analysis.graphs.length) {
        area.innerHTML = '<div class="hint">无有效聚合（可能存在环或事件被全部忽略）。</div>';
        return;
    }
    area.innerHTML = analysis.graphs.map((graph) => svgForGraph(graph, analysis)).join("") + `
        <div class="legend">
            <span class="lg-causal">显式因果边</span>
            <span class="lg-order">服务内顺序</span>
            <span class="lg-ph">占位</span>
            <span class="lg-fail">失败事件</span>
        </div>`;
}

function svgForGraph(graph, analysis) {
    const nodeMap = new Map(graph.nodes.map((node) => [node.uid, node]));
    const levels = new Map();
    const incoming = new Map(graph.nodes.map((node) => [node.uid, 0]));
    graph.edges.forEach((edge) => incoming.set(edge.to, (incoming.get(edge.to) || 0) + 1));
    const queue = graph.nodes.filter((node) => (incoming.get(node.uid) || 0) === 0)
        .map((node) => node.uid);
    queue.forEach((uid) => levels.set(uid, 0));
    const working = [...queue];
    const indegree = new Map(incoming);
    while (working.length) {
        const uid = working.shift();
        graph.edges.filter((edge) => edge.from === uid).forEach((edge) => {
            levels.set(edge.to, Math.max(levels.get(edge.to) || 0, (levels.get(uid) || 0) + 1));
            indegree.set(edge.to, indegree.get(edge.to) - 1);
            if (indegree.get(edge.to) === 0) working.push(edge.to);
        });
    }

    const columns = new Map();
    graph.nodes.forEach((node) => {
        const level = levels.get(node.uid) ?? 0;
        if (!columns.has(level)) columns.set(level, []);
        columns.get(level).push(node);
    });

    const nodeWidth = 150, nodeHeight = 58, gapX = 60, gapY = 24;
    const width = Math.max(1, columns.size) * (nodeWidth + gapX) + 20;
    const maxRows = Math.max(...[...columns.values()].map((group) => group.length));
    const height = maxRows * (nodeHeight + gapY) + 20;

    const positions = new Map();
    [...columns.entries()].sort((a, b) => a[0] - b[0]).forEach(([level, nodes]) => {
        nodes.sort((a, b) => a.service.localeCompare(b.service) || a.seq - b.seq);
        nodes.forEach((node, row) => {
            positions.set(node.uid, {
                x: 10 + level * (nodeWidth + gapX),
                y: 10 + row * (nodeHeight + gapY),
            });
        });
    });

    const failedUid = analysis.replay.earliestFailure
        && analysis.replay.earliestFailure.aggregateKey === graph.key
        ? analysis.replay.earliestFailure.eventUid : null;

    const edgeMarkup = graph.edges.map((edge) => {
        const a = positions.get(edge.from);
        const b = positions.get(edge.to);
        if (!a || !b) return "";
        const x1 = a.x + nodeWidth, y1 = a.y + nodeHeight / 2;
        const x2 = b.x, y2 = b.y + nodeHeight / 2;
        const color = edge.reason === "CAUSAL" ? "#5b9dff" : "#f0b429";
        return `<line x1="${x1}" y1="${y1}" x2="${x2 - 6}" y2="${y2}" stroke="${color}"
                stroke-width="1.6" marker-end="url(#arrow-${graph.key.replace(/[^a-z0-9]/gi, "")})"
                ${edge.reason === "SERVICE_ORDER" ? 'stroke-dasharray="5 4"' : ""}/>`;
    }).join("");

    const nodeMarkup = graph.nodes.map((node) => {
        const point = positions.get(node.uid);
        const fill = node.placeholder ? "#3a2e55" : "#1d2740";
        const stroke = node.uid === failedUid ? "#ef5b5b"
            : node.placeholder ? "#b48cff" : "#2a3550";
        const label = node.placeholder ? "占位 " + node.uid
            : `${node.uid} · ${node.service}#${node.seq}`;
        const clock = node.wallClock ? node.wallClock.replace("T", " ").replace("Z", "Z") : "";
        return `
            <g>
                <rect x="${point.x}" y="${point.y}" width="${nodeWidth}" height="${nodeHeight}"
                      rx="8" fill="${fill}" stroke="${stroke}" stroke-width="1.5"/>
                <text x="${point.x + 9}" y="${point.y + 20}">${escapeHtml(label)}</text>
                <text x="${point.x + 9}" y="${point.y + 37}" fill="#8a97b4">${escapeHtml(node.type)}</text>
                <text x="${point.x + 9}" y="${point.y + 52}" fill="#5e6b8a">${escapeHtml(clock)}</text>
            </g>`;
    }).join("");

    return `
        <h3 style="margin:6px 0;font-size:13px;">聚合 ${escapeHtml(graph.key)}</h3>
        <svg width="100%" viewBox="0 0 ${width} ${height}" style="max-width:100%;overflow-x:auto;">
            <defs><marker id="arrow-${graph.key.replace(/[^a-z0-9]/gi, "")}" markerWidth="8" markerHeight="8"
                refX="7" refY="3" orient="auto">
                <path d="M0,0 L7,3 L0,6 Z" fill="#8aa0c8"/></marker></defs>
            ${edgeMarkup}${nodeMarkup}
        </svg>`;
}

function renderFailure(analysis) {
    const area = document.getElementById("failure-area");
    const replay = analysis.replay;
    if (!replay.earliestFailure) {
        area.innerHTML = '<div class="ok-card">在当前假设与拓扑种子下，所有聚合不变量成立。</div>';
        return;
    }
    const failure = replay.earliestFailure;
    const slice = replay.minimalSlice;
    const pairs = (replay.concurrentPairs || []).map((pair) => `
        <tr>
            <td><code>${escapeHtml(pair.eventA)}</code> ⇄ <code>${escapeHtml(pair.eventB)}</code></td>
            <td class="${pair.changesOutcome ? "pair-change" : "pair-safe"}">
                ${pair.changesOutcome ? "交换会改变结论" : "可安全交换"}</td>
            <td>${escapeHtml(pair.note)}</td>
        </tr>`).join("");

    area.innerHTML = `
        <div class="failure-card">
            <b>聚合</b> ${escapeHtml(failure.aggregateKey)}<br>
            <b>失败事件</b> <code>${escapeHtml(failure.eventUid)}</code>
            （${escapeHtml(failure.eventType)}）从状态 <code>${escapeHtml(failure.fromState)}</code> 无合法转换<br>
            <span class="hint">${escapeHtml(failure.message)}</span>
        </div>
        <h4>最小可复现切片</h4>
        <table>
            <thead><tr><th>#</th><th>事件</th><th>类型</th><th>from</th><th>to</th></tr></thead>
            <tbody>${(slice.steps || []).map((step) => `
                <tr>
                    <td>${step.index}</td>
                    <td><code>${escapeHtml(step.eventUid)}</code>
                        ${step.placeholder ? '<span class="tag ph">占位</span>' : ""}</td>
                    <td>${escapeHtml(step.type)}</td>
                    <td>${escapeHtml(step.fromState)}</td>
                    <td>${escapeHtml(step.toState)}</td>
                </tr>`).join("")}
            </tbody>
        </table>
        <p class="hint">因果祖先：${(slice.causalEventUids || []).map(escapeHtml).join(", ") || "（无）"}</p>
        <p class="hint">${escapeHtml(slice.explanation)}</p>
        <h4>并发事件可交换性</h4>
        <table><thead><tr><th>事件对</th><th>结论</th><th>说明</th></tr></thead>
            <tbody>${pairs || '<tr><td colspan="3" class="hint">失败前缀内无并发事件对。</td></tr>'}</tbody></table>`;
}

function renderAggregates(analysis) {
    const area = document.getElementById("aggregate-area");
    const traces = Object.values(analysis.replay.aggregates || {});
    if (!traces.length) {
        area.innerHTML = '<div class="hint">无聚合轨迹。</div>';
        return;
    }
    area.innerHTML = traces.map((trace) => `
        <div class="hypo-block">
            <h4>${escapeHtml(trace.key)}
                <span class="tag info">${escapeHtml(trace.currentState)}</span></h4>
            <table>
                <thead><tr><th>#</th><th>事件</th><th>转换</th><th>结果</th></tr></thead>
                <tbody>${trace.steps.map((step) => `
                    <tr>
                        <td>${step.index}</td>
                        <td><code>${escapeHtml(step.eventUid)}</code>
                            ${step.placeholder ? '<span class="tag ph">占位</span>' : ""}</td>
                        <td>${escapeHtml(step.fromState)} → ${escapeHtml(step.toState)}
                            <div class="hint">${escapeHtml(step.type)}</div></td>
                        <td>${step.applied ? '<span class="tag good">applied</span>'
                            : step.placeholder ? '<span class="tag ph">non-business</span>'
                            : `<span class="tag bad">blocked</span><div class="hint">${escapeHtml(step.note || "")}</div>`}</td>
                    </tr>`).join("")}
                </tbody>
            </table>
        </div>`).join("");
}

function renderInterleavings(analysis) {
    const area = document.getElementById("interleaving-area");
    const report = analysis.replay.interleavings;
    const cyclic = analysis.replay.cyclicKeys || [];
    let html = "";
    if (cyclic.length) {
        html += `<div class="banner warn">检测到因果环，以下聚合无法线性化：<b>${cyclic.map(escapeHtml).join(", ")}</b></div>`;
    }
    html += `<p class="hint">每个聚合独立枚举合法拓扑序（聚合间天然并发）。</p>
        <p>已枚举 <b>${report.enumerated}</b> 个（边界 ${report.requestedBound}）
           ${report.truncatedUnknown
            ? ' <span class="unknown">达到边界：剩余可能性 UNKNOWN</span>'
            : ' · 已穷尽该边界内全部合法序'}</p>
        <table><thead><tr><th>聚合</th><th>线性化</th><th>结果</th></tr></thead><tbody>
        ${report.outcomes.map((outcome) => `
            <tr>
                <td>${escapeHtml(outcome.aggregateKey)}</td>
                <td>${outcome.order.map(escapeHtml).join(" → ")}</td>
                <td>${outcome.failed
                    ? `<span class="tag bad">失败 @ ${escapeHtml(outcome.failedEventUid)}</span>`
                    : '<span class="tag good">通过</span>'}</td>
            </tr>`).join("")}
        </tbody></table>`;
    area.innerHTML = html;
}

function allKnownEventUids() {
    const uids = new Set();
    (state.analysis?.graphs || []).forEach((graph) =>
        graph.nodes.forEach((node) => {
            if (!node.placeholder) uids.add(node.uid);
        }));
    return [...uids];
}

function renderHypothesisEditor() {
    const area = document.getElementById("hypo-area");
    const hypo = state.hypothesis;
    const known = allKnownEventUids();

    const ignoreChips = hypo.ignoreEventUids.map((uid) =>
        chip(uid, () => {
            hypo.ignoreEventUids = hypo.ignoreEventUids.filter((item) => item !== uid);
            renderHypothesisEditor();
        })).join("");

    const placeholderChips = hypo.placeholders.map((request) =>
        chip(`${request.missingParentUid}@${request.key}`, () => {
            hypo.placeholders = hypo.placeholders
                .filter((item) => item.missingParentUid !== request.missingParentUid);
            renderHypothesisEditor();
        })).join("");

    const remapChips = hypo.remappedKeys.map((request) =>
        chip(`${request.eventUid}→${request.newKey}`, () => {
            hypo.remappedKeys = hypo.remappedKeys
                .filter((item) => item.eventUid !== request.eventUid);
            renderHypothesisEditor();
        })).join("");

    const uidOptions = known.map((uid) => `<option value="${escapeHtml(uid)}">`).join("");

    area.innerHTML = `
        <div class="hypo-block">
            <h4>① 忽略重复投递 / 序号冲突内容</h4>
            <div>${ignoreChips || '<span class="hint">无</span>'}</div>
            <div class="row-form">
                <input list="uid-list" id="add-ignore" placeholder="选择事件 uid">
                <button id="btn-add-ignore">添加忽略</button>
            </div>
        </div>
        <div class="hypo-block">
            <h4>② 补入缺失父项占位（永不视为业务事件）</h4>
            <div>${placeholderChips || '<span class="hint">无</span>'}</div>
            <div class="row-form">
                <input id="add-ph-uid" placeholder="缺失父项 uid">
                <input id="add-ph-key" placeholder="关联键">
                <input id="add-ph-service" placeholder="服务(可空)">
                <button id="btn-add-ph">添加占位</button>
            </div>
        </div>
        <div class="hypo-block">
            <h4>③ 调整关联键（派生重放，不改原值）</h4>
            <div>${remapChips || '<span class="hint">无</span>'}</div>
            <div class="row-form">
                <input list="uid-list" id="add-remap-uid" placeholder="事件 uid">
                <input id="add-remap-key" placeholder="新关联键">
                <button id="btn-add-remap">添加改键</button>
            </div>
        </div>
        <datalist id="uid-list">${uidOptions}</datalist>
        <h4>摄取异常（原始证据分类）</h4>
        <div>${renderAnomalies()}</div>`;

    document.getElementById("btn-add-ignore").onclick = () => {
        const value = document.getElementById("add-ignore").value.trim();
        if (value && !hypo.ignoreEventUids.includes(value)) {
            hypo.ignoreEventUids.push(value);
            renderHypothesisEditor();
        }
    };
    document.getElementById("btn-add-ph").onclick = () => {
        const uid = document.getElementById("add-ph-uid").value.trim();
        const key = document.getElementById("add-ph-key").value.trim();
        const service = document.getElementById("add-ph-service").value.trim();
        if (uid && key) {
            hypo.placeholders.push({
                missingParentUid: uid, key,
                service: service || null, expectedSeq: null,
            });
            renderHypothesisEditor();
        }
    };
    document.getElementById("btn-add-remap").onclick = () => {
        const uid = document.getElementById("add-remap-uid").value.trim();
        const newKey = document.getElementById("add-remap-key").value.trim();
        if (uid && newKey) {
            hypo.remappedKeys = hypo.remappedKeys.filter((item) => item.eventUid !== uid);
            hypo.remappedKeys.push({eventUid: uid, newKey});
            renderHypothesisEditor();
        }
    };
}

function chip(label, onRemove) {
    const node = document.createElement("span");
    node.className = "chip";
    node.innerHTML = `${escapeHtml(label)} <button title="移除">×</button>`;
    node.querySelector("button").onclick = onRemove;
    return node.outerHTML;
}

function renderAnomalies() {
    const anomalies = state.analysis?.anomalies || [];
    if (!anomalies.length) return '<span class="hint">未检测到重复 / 迟到 / 缺父项 / 同序号冲突。</span>';
    const label = {
        DUPLICATE_DELIVERY: "重复投递",
        SEQ_CONTENT_CONFLICT: "同序号内容冲突",
        MISSING_PARENT: "缺父项",
        LATE_DELIVERY: "迟到/乱序",
    };
    return `<table><tbody>${anomalies.map((anomaly) => `
        <tr>
            <td><span class="tag warn">${label[anomaly.kind] || anomaly.kind}</span></td>
            <td><code>${escapeHtml(anomaly.eventUid)}</code> ${escapeHtml(anomaly.serviceSeq || "")}</td>
            <td class="hint">${escapeHtml(anomaly.detail)}</td>
        </tr>`).join("")}</tbody></table>`;
}

document.getElementById("btn-refresh").onclick = loadAll;

document.getElementById("btn-sample").onclick = async () => {
    const log = await api("/api/logs/sample", {method: "POST"});
    state.selectedLogId = log.id;
    await loadAll();
    await selectLog(log.id);
};

document.getElementById("btn-ingest").onclick = async () => {
    const name = document.getElementById("ingest-name").value.trim() || "ingested-log";
    let events;
    try {
        events = JSON.parse(document.getElementById("ingest-json").value);
    } catch (error) {
        alert("JSON 解析失败：" + error.message);
        return;
    }
    const log = await api("/api/logs", {
        method: "POST",
        body: JSON.stringify({name, events}),
    });
    state.selectedLogId = log.id;
    await loadAll();
    await selectLog(log.id);
};

document.getElementById("btn-new-session").onclick = async () => {
    if (!state.selectedLogId) {
        alert("请先选择一个日志。");
        return;
    }
    const name = prompt("会话名称", "session@log" + state.selectedLogId);
    if (name === null) return;
    const session = await api("/api/sessions", {
        method: "POST",
        body: JSON.stringify({name, logId: state.selectedLogId}),
    });
    await loadAll();
    await selectSession(session.id);
};

document.getElementById("btn-replay").onclick = async () => {
    if (state.selectedSession) {
        await runReplay({sessionId: state.selectedSession.id});
    } else if (state.selectedLogId) {
        await runReplay({sessionId: null, logId: state.selectedLogId});
    }
};

document.getElementById("btn-fixed-replay").onclick = async () => {
    const text = document.getElementById("fixed-order").value.trim();
    const fixedOrder = text ? text.split(",").map((item) => item.trim()).filter(Boolean) : null;
    if (state.selectedSession) {
        await runReplay({sessionId: state.selectedSession.id, fixedOrder});
    } else if (state.selectedLogId) {
        await runReplay({sessionId: null, logId: state.selectedLogId, fixedOrder});
    }
};

document.getElementById("bound").onchange = () => {
    if (state.analysis) document.getElementById("btn-replay").click();
};

document.getElementById("btn-save").onclick = async () => {
    if (!state.selectedSession) return;
    const payload = {
        name: document.getElementById("session-name").value,
        hypothesis: state.hypothesis,
        expectedRevision: state.selectedSession.revision,
    };
    try {
        const updated = await api(`/api/sessions/${state.selectedSession.id}`, {
            method: "PUT",
            body: JSON.stringify(payload),
        });
        state.conflict = null;
        document.getElementById("conflict-banner").classList.add("hidden");
        await selectSession(updated.id);
    } catch (error) {
        if (error.status === 409) {
            state.conflict = error.body;
            document.getElementById("conflict-text").textContent = error.message;
            document.getElementById("conflict-banner").classList.remove("hidden");
        } else {
            alert("保存失败：" + error.message);
        }
    }
};

document.getElementById("btn-conflict-reload").onclick = async () => {
    if (!state.selectedSession) return;
    const latest = await api(`/api/sessions/${state.selectedSession.id}`);
    const their = JSON.stringify(latest.hypothesis, null, 2);
    const mine = JSON.stringify(state.hypothesis, null, 2);
    alert(`最新 rev ${latest.revision} 名称「${latest.name}」\n\n对方假设:\n${their}\n\n你的本地假设:\n${mine}`);
};

document.getElementById("btn-conflict-merge").onclick = async () => {
    if (!state.selectedSession) return;
    const latest = await api(`/api/sessions/${state.selectedSession.id}`);
    const merged = mergeHypotheses(latest.hypothesis, state.hypothesis);
    state.hypothesis = merged;
    await selectSession(latest.id);
    await document.getElementById("btn-replay").click();
    alert("已把你的忽略/占位/改键合并到最新修订，请核对后再次保存。");
};

function mergeHypotheses(base, mine) {
    const union = (a, b) => [...new Set([...(a || []), ...(b || [])])];
    const placeholders = new Map();
    [...(base.placeholders || []), ...(mine.placeholders || [])].forEach((item) =>
        placeholders.set(item.missingParentUid, item));
    const remaps = new Map();
    [...(base.remappedKeys || []), ...(mine.remappedKeys || [])].forEach((item) =>
        remaps.set(item.eventUid, item));
    return {
        ignoreEventUids: union(base.ignoreEventUids, mine.ignoreEventUids),
        placeholders: [...placeholders.values()],
        remappedKeys: [...remaps.values()],
        notes: [base.notes, mine.notes].filter(Boolean).join(" | ") || "",
    };
}

document.getElementById("btn-derive").onclick = async () => {
    if (!state.selectedSession) return;
    const newLogId = Number(prompt(
        "派生到哪个新日志 id？（旧会话不重绑，只创建派生会话）", state.selectedLogId + 1));
    if (!newLogId) return;
    try {
        const derived = await api(`/api/sessions/${state.selectedSession.id}/derive`, {
            method: "POST",
            body: JSON.stringify({newLogId, hypothesis: state.hypothesis}),
        });
        await loadAll();
        await selectSession(derived.id);
    } catch (error) {
        alert("派生失败：" + error.message);
    }
};

document.getElementById("btn-export").onclick = async () => {
    if (!state.selectedSession) {
        alert("请先选择会话再导出。");
        return;
    }
    const doc = await api(`/api/sessions/${state.selectedSession.id}/export`);
    document.getElementById("export-text").value = JSON.stringify(doc, null, 2);
    document.getElementById("export-dialog").showModal();
};
document.getElementById("btn-export-close").onclick = () =>
    document.getElementById("export-dialog").close();

loadAll().then(() => {
    if (state.logs.length) {
        selectLog(state.logs[state.logs.length - 1].id);
    }
}).catch((error) => alert("初始化失败：" + error.message));
