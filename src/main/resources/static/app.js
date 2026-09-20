const state = { evidence: null, sessions: [], session: null, preview: null };

const $ = (id) => document.getElementById(id);

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
    body: options.body ? JSON.stringify(options.body) : undefined
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.message || `HTTP ${response.status}`);
    error.body = data;
    throw error;
  }
  return data;
}

function hypothesis() {
  return {
    ignoreDuplicates: $("ignoreDup").checked,
    fillMissingParents: $("fillMissing").checked,
    keyAdjustments: JSON.parse($("adjustments").value || "{}"),
    seedOrder: $("seedOrder").value.split(/\r?\n/).map(x => x.trim()).filter(Boolean),
    interleavingBound: Number($("bound").value || 100)
  };
}

function render() {
  renderEvidence();
  renderSessions();
  renderSession();
  renderPreview();
}

function renderEvidence() {
  if (!state.evidence) return;
  $("logFingerprint").textContent = `日志指纹 ${state.evidence.fingerprint}（${state.evidence.receiptCount} 条原始收据）`;
  const rows = state.evidence.receipts.map(event => `
    <tr>
      <td>${event.eventId}<br><small>${event.receiptId}</small></td>
      <td>${event.service}#${event.seq}<br><small>墙钟:${event.timestamp ?? "无"}</small></td>
      <td>${event.key}<br><small>${event.type}${event.parentId ? ` ← ${event.parentId}` : ""}</small></td>
      <td><span class="pill ${severity(event.classification)}">${event.classification}</span><br>${event.classificationReason ?? ""}</td>
    </tr>`).join("");
  $("evidenceTable").innerHTML = `<table><thead><tr><th>身份</th><th>服务序号/时间</th><th>聚合/父项</th><th>接收分类</th></tr></thead><tbody>${rows}</tbody></table>`;
  const candidate = state.session?.candidates?.find(c => c.id === state.session?.workspace?.selectedCandidateId)
    || state.session?.candidates?.at(-1);
  const order = candidate?.result?.deterministicOrder || [];
  const byId = new Map(state.evidence.receipts.map(x => [x.eventId, x]));
  const nodes = order.length ? order : [...new Set(state.evidence.receipts.filter(x => x.materialized).map(x => x.eventId))];
  const byService = {};
  state.evidence.receipts.filter(x => x.materialized).forEach(event => {
    (byService[event.service] ||= []).push(event);
  });
  const serviceEdges = [];
  Object.entries(byService).forEach(([service, events]) => {
    events.sort((a, b) => a.seq - b.seq || a.eventId.localeCompare(b.eventId));
    for (let i = 1; i < events.length; i += 1) {
      serviceEdges.push({ from: events[i - 1].eventId, to: events[i].eventId, kind: `service:${service}` });
    }
  });
  const causalEdges = [...new Set(state.evidence.receipts.filter(x => x.materialized && x.parentId).map(x => `${x.parentId}->${x.eventId}`))]
    .map(text => { const [from, to] = text.split("->"); return { from, to, kind: "causal" }; });
  const edges = [...causalEdges, ...serviceEdges];
  const nodeHtml = nodes.map((id, index) => {
    const event = byId.get(id);
    const placeholder = id.startsWith("placeholder:");
    const failed = candidate?.result?.earliestFailureEventId === id;
    const incoming = edges.filter(edge => edge.to === id).map(edge => `${edge.kind}:${edge.from}`).join("<br>");
    return `<span class="node ${placeholder ? "placeholder" : ""} ${failed ? "failed" : ""}">
      #${index + 1} <b>${id}</b>
      <small>${placeholder ? "占位，不是业务事件" : `${event?.service}#${event?.seq} · ${event?.key} · ${event?.type}`}</small>
      <small>${placeholder ? "等待真实父项" : event?.parentId ? `因果父项 ${event.parentId}` : "根事件"}</small>
      ${incoming ? `<small class="edges">↤ ${incoming}</small>` : ""}
    </span>`;
  }).join('<span class="edge">→</span>');
  const edgeHtml = `<div class="edge-list"><b>DAG 边（不使用墙钟排序）</b>${edges.map(edge =>
    `<span class="edge-pill ${edge.kind === "causal" ? "causal" : "service"}">${edge.from} → ${edge.to} <small>${edge.kind}</small></span>`
  ).join("")}</div>`;
  $("graph").innerHTML = nodeHtml + edgeHtml;
}

function renderSessions() {
  const select = $("sessionSelect");
  select.innerHTML = state.sessions.map(s => `<option value="${s.id}">${s.id} · v${s.ruleVersion} · ${s.status}</option>`).join("");
  if (state.session) select.value = state.session.id;
}

function renderSession() {
  const session = state.session;
  if (!session) {
    $("sessionDetail").innerHTML = "<p>固定当前日志后可保存修复候选。</p>";
    return;
  }
  const workspace = session.workspace;
  const candidates = session.candidates.map(candidate => {
    const result = candidate.result;
    return `<div class="candidate">
      <h3>${candidate.id} <span class="pill ${candidate.status.includes("INVALID") ? "error" : "ok"}">${candidate.status}</span></h3>
      <p>${candidate.invalidationReason || ""}</p>
      <p><b>结论：</b>${result.outcome} ${result.earliestFailureCode ? `· ${result.earliestFailureCode} @ ${result.earliestFailureEventId} rank ${result.earliestFailureRank}` : ""}</p>
      <p><b>最早失败：</b>${result.earliestFailureMessage || "无"}</p>
      <p><b>最小切片：</b>${result.minimalSlice.map(x => x.eventId).join(", ") || "无"}</p>
      <p><b>确定性种子：</b><code>${result.deterministicSeed}</code></p>
      <details><summary>诊断、状态与并发分析</summary><pre>${JSON.stringify({
        diagnoses: result.diagnoses,
        aggregateStates: result.aggregateStates,
        concurrentPairs: result.concurrentPairs,
        boundReached: result.boundReached,
        exploredCount: result.exploredCount
      }, null, 2)}</pre></details>
      <div class="row wrap">
        <button onclick="mergeCandidate('${candidate.id}', ${candidate.revision})">合并该候选</button>
        <button class="secondary" onclick="fixedReplay('${candidate.id}')">按文本种子固定重放</button>
        <button onclick="downloadCandidate('${candidate.id}')">导出</button>
      </div>
    </div>`;
  }).join("");
  $("sessionDetail").innerHTML = `
    <div class="row wrap">
      <h3>${session.id}</h3>
      <span class="pill ok">${workspace.status}</span>
      <span>workspace revision ${workspace.revision}</span>
    </div>
    <p class="fingerprint">规则 ${session.ruleCode} v${session.ruleVersion} / ${session.ruleFingerprint}<br>
    绑定日志 ${session.logFingerprint}${session.currentLogFingerprint !== session.logFingerprint ? "<br><b>新日志只能派生，不会覆盖此会话。</b>" : ""}</p>
    <p>${workspace.note}</p>
    ${candidates}`;
}

function renderPreview() {
  if (!state.preview) {
    $("replayResult").innerHTML = "";
    return;
  }
  const result = state.preview.result;
  $("replayResult").innerHTML = `<div class="${result.outcome === "FAILED" ? "conflict" : ""}">
    <h3>${result.outcome} ${result.boundReached ? "· 达到交错上限，未探索部分为未知" : ""}</h3>
    <p>${result.earliestFailureMessage || "所有已探索拓扑均通过"}</p>
    <p><b>聚合状态</b></p><pre>${JSON.stringify(result.aggregateStates, null, 2)}</pre>
    <p><b>最小切片</b></p><pre>${JSON.stringify(result.minimalSlice, null, 2)}</pre>
    <p><b>并发可交换性</b></p><pre>${JSON.stringify(result.concurrentPairs, null, 2)}</pre>
    <p><b>有界交错</b></p><pre>${JSON.stringify(result.linearizations, null, 2)}</pre>
  </div>`;
}

function severity(code) {
  if (code.includes("CONFLICT") || code.includes("FAIL")) return "error";
  if (code.includes("DUPLICATE") || code.includes("GAP")) return "warning";
  if (code.includes("NEW")) return "ok";
  return "unknown";
}

async function refresh(selectId) {
  state.evidence = await api("/api/events");
  state.sessions = await api("/api/sessions");
  const wanted = selectId || state.session?.id || state.sessions.at(-1)?.id;
  state.session = wanted ? await api(`/api/sessions/${wanted}`) : null;
  render();
}

$("refreshBtn").onclick = () => refresh();
$("sessionSelect").onchange = async (event) => {
  state.session = await api(`/api/sessions/${event.target.value}`);
  renderSession();
};
$("ingestBtn").onclick = async () => {
  try {
    const events = JSON.parse($("eventInput").value || "[]");
    const result = await api("/api/events", { method: "POST", body: { clientBatchId: $("batchId").value, events } });
    $("ingestResult").innerHTML = `<pre>${JSON.stringify(result, null, 2)}</pre>`;
    await refresh();
  } catch (error) {
    alert(`${error.message}\n${JSON.stringify(error.body, null, 2)}`);
  }
};
$("createSessionBtn").onclick = async () => {
  const created = await api("/api/sessions", { method: "POST" });
  await refresh(created.id);
};
$("deriveBtn").onclick = async () => {
  if (!state.session) return alert("先选择旧会话");
  const derived = await api(`/api/sessions/${state.session.id}/derive`, { method: "POST" });
  await refresh(derived.id);
};
$("previewBtn").onclick = async () => {
  try {
    state.preview = await api("/api/replay-preview", { method: "POST", body: { hypothesis: hypothesis() } });
    renderPreview();
  } catch (error) { alert(`${error.message}\n${JSON.stringify(error.body)}`); }
};
$("saveCandidateBtn").onclick = async () => {
  if (!state.session) return alert("先固定调试会话");
  try {
    const saved = await api(`/api/sessions/${state.session.id}/candidates`, {
      method: "POST",
      body: { name: "UI candidate", hypothesis: hypothesis(), expectedWorkspaceRevision: state.session.workspace.revision }
    });
    await refresh(state.session.id);
    state.preview = saved;
    renderPreview();
  } catch (error) { showConflict(error); }
};

window.mergeCandidate = async (candidateId, candidateRevision) => {
  try {
    await api(`/api/sessions/${state.session.id}/merge`, {
      method: "POST",
      body: { candidateId, note: "merged from UI", expectedWorkspaceRevision: state.session.workspace.revision, candidateRevision }
    });
    await refresh(state.session.id);
  } catch (error) { showConflict(error); }
};

window.fixedReplay = async (candidateId) => {
  const fixedOrder = $("seedOrder").value.split(/\r?\n/).map(x => x.trim()).filter(Boolean);
  const result = await api(`/api/sessions/${state.session.id}/candidates/${candidateId}/fixed-replay`, {
    method: "POST",
    body: { fixedOrder, expectedWorkspaceRevision: state.session.workspace.revision }
  });
  state.preview = result;
  renderPreview();
};

window.downloadCandidate = async (candidateId) => {
  const exported = await api(`/api/sessions/${state.session.id}/export`);
  const blob = new Blob([JSON.stringify(exported, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `${state.session.id}-${candidateId}.json`;
  link.click();
  URL.revokeObjectURL(url);
};

function showConflict(error) {
  const body = error.body || {};
  const message = `${error.message}\n${JSON.stringify(body, null, 2)}\n\n点击确定将重新加载冲突内容。`;
  alert(message);
  refresh(state.session?.id);
}

$("eventInput").value = JSON.stringify([
  { eventId: "e1", service: "orders", seq: 1, key: "order-1", type: "CREATE", payload: {}, timestamp: 1000 },
  { eventId: "e2", service: "payments", seq: 1, key: "pay-1", type: "COMPLETE", payload: {}, parentId: "e1", timestamp: 900 }
], null, 2);

refresh();
