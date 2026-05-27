// screens.jsx — Goals, Add/Categorize flow, Tx detail screen

// ─────────────────────────────────────────────────────────────
// GOALS — savings, debt payoff, multi-ring overview
// ─────────────────────────────────────────────────────────────
function GoalsScreen() {
  const totalSaved = MFV_GOALS.reduce((s, g) => s + g.saved, 0);
  const totalTarget = MFV_GOALS.reduce((s, g) => s + g.target, 0);
  const totalPct = (totalSaved / totalTarget) * 100;

  return (
    <div style={{ padding: '8px 18px 12px' }}>
      <div className="between" style={{ marginBottom: 18 }}>
        <div>
          <div style={{ fontSize: 12, color: 'var(--text-2)' }}>Saving toward</div>
          <div style={{ fontSize: 22, fontWeight: 700, letterSpacing: -0.6, marginTop: 2 }}>4 goals</div>
        </div>
        <button className="tap" style={{
          height: 36, padding: '0 12px', borderRadius: 12, cursor: 'pointer',
          background: 'var(--text)', color: 'var(--bg)', border: 'none',
          fontFamily: 'var(--font-sans)', fontSize: 12, fontWeight: 600,
          display: 'flex', alignItems: 'center', gap: 4,
        }}>
          <MFVIcon name="plus" size={13} stroke={2.5} /> New
        </button>
      </div>

      {/* Aggregate ring + summary */}
      <div className="card" style={{ padding: 18, marginBottom: 14 }}>
        <div className="row" style={{ gap: 16 }}>
          <div style={{ position: 'relative' }}>
            <MFVDonut
              segments={MFV_GOALS.map((g) => ({ value: g.saved, color: g.color }))}
              size={110} thickness={10}
              track="var(--track)"
            />
            <div style={{
              position: 'absolute', inset: 0, display: 'flex',
              flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
              gap: 0,
            }}>
              <div style={{ fontSize: 10, color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 600 }}>Saved</div>
              <div className="num-display" style={{ fontSize: 22, fontWeight: 600, letterSpacing: -0.6, lineHeight: 1 }}>
                ${(totalSaved/1000).toFixed(1)}k
              </div>
              <div className="num" style={{ fontSize: 10, color: 'var(--text-2)', marginTop: 2 }}>
                of ${(totalTarget/1000).toFixed(1)}k
              </div>
            </div>
          </div>
          <div style={{ flex: 1 }}>
            <div className="row" style={{ gap: 6, marginBottom: 10 }}>
              <span style={{
                display: 'inline-flex', alignItems: 'center', gap: 3,
                padding: '3px 8px', borderRadius: 999,
                background: 'rgba(52,224,161,0.14)', color: 'var(--c-positive)',
                fontSize: 11, fontWeight: 600,
              }}>
                <MFVIcon name="flame" size={10} stroke={2.4} /> 4 mo streak
              </span>
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-2)', lineHeight: 1.45, textWrap: 'pretty' }}>
              At <b className="num" style={{ color: 'var(--text)' }}>$1,130</b>/mo you're on pace for all 4 goals.
            </div>
            <div className="row" style={{ gap: 6, marginTop: 10 }}>
              {MFV_GOALS.map((g) => (
                <div key={g.id} style={{
                  width: 8, height: 8, borderRadius: 2, background: g.color,
                }} />
              ))}
              <span style={{ fontSize: 10, color: 'var(--text-3)', marginLeft: 2 }}>
                {Math.round(totalPct)}% complete
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* Per-goal cards */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {MFV_GOALS.map((g, i) => <GoalCard key={g.id} g={g} featured={i === 0} />)}
      </div>
    </div>
  );
}

function GoalCard({ g, featured }) {
  const pct = (g.saved / g.target) * 100;
  const remaining = g.target - g.saved;
  const monthsLeft = Math.ceil(remaining / g.monthly);
  return (
    <div className="card" style={{
      padding: 14,
      background: featured ? `linear-gradient(135deg, color-mix(in oklch, ${g.color} 14%, transparent), transparent 70%), var(--surface)` : 'var(--surface)',
      borderColor: featured ? `color-mix(in oklch, ${g.color} 30%, var(--border))` : 'var(--border)',
    }}>
      <div className="row" style={{ gap: 12 }}>
        <MFVRing pct={pct} size={56} thickness={5} color={g.color} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="between">
            <div style={{ fontSize: 13, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{g.label}</div>
            <div style={{
              padding: '2px 7px', borderRadius: 999, fontSize: 10, fontWeight: 600,
              background: `color-mix(in oklch, ${g.color} 18%, transparent)`,
              color: g.color,
            }}>{g.due}</div>
          </div>
          <div className="num" style={{ fontSize: 11, color: 'var(--text-2)', marginTop: 2 }}>
            <span style={{ color: 'var(--text)', fontWeight: 600 }}>${g.saved.toLocaleString()}</span>
            {' '}of ${g.target.toLocaleString()}
          </div>
          <div className="row" style={{ gap: 6, marginTop: 8 }}>
            <MFVBar pct={pct} color={g.color} height={4} />
          </div>
          <div className="between" style={{ marginTop: 8 }}>
            <span style={{ fontSize: 10, color: 'var(--text-3)' }}>
              ${g.monthly}/mo · {monthsLeft}mo left
            </span>
            <button className="tap" style={{
              padding: '4px 10px', borderRadius: 999, cursor: 'pointer',
              background: 'var(--chip-bg)', color: 'var(--text)', border: 'none',
              fontFamily: 'var(--font-sans)', fontSize: 10, fontWeight: 600,
            }}>+ Add</button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// ADD / CATEGORIZE flow — 3 steps. Each artboard can render one step,
// or pass interactive=true to step through.
// ─────────────────────────────────────────────────────────────
function AddTxFlow({ startStep = 1, interactive = false }) {
  const [step, setStep] = React.useState(startStep);
  const [chosenCat, setChosenCat] = React.useState('shopping');
  const go = (n) => interactive ? setStep(n) : null;

  // Pending tx that just came in via email
  const pending = {
    merchant: 'Apple Store',
    amount: -329.00,
    when: 'Just now',
    suggested: 'shopping',
    sender: 'no_reply@email.apple.com',
    subject: 'Your receipt from Apple',
  };

  return (
    <div className="col" style={{ height: '100%', padding: '12px 18px 12px' }}>
      {/* Step indicator */}
      <div className="row" style={{ gap: 6, marginBottom: 18 }}>
        {[1, 2, 3].map((s) => (
          <div key={s} className="row" style={{ gap: 6, flex: 1 }}>
            <div style={{
              width: 22, height: 22, borderRadius: 7,
              background: s <= step ? 'var(--text)' : 'var(--surface-2)',
              color: s <= step ? 'var(--bg)' : 'var(--text-3)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontFamily: 'var(--font-sans)', fontSize: 11, fontWeight: 600,
              transition: 'background .2s',
            }}>
              {s < step ? <MFVIcon name="check" size={11} stroke={3} /> : s}
            </div>
            {s < 3 && <div style={{ flex: 1, height: 1, background: s < step ? 'var(--text)' : 'var(--border)' }} />}
          </div>
        ))}
      </div>

      <div style={{ flex: 1, overflowY: 'auto' }} className="scroll">
        {step === 1 && <AddStep1 pending={pending} onNext={() => go(2)} />}
        {step === 2 && <AddStep2 pending={pending} chosen={chosenCat} onChoose={setChosenCat} onNext={() => go(3)} />}
        {step === 3 && <AddStep3 pending={pending} cat={chosenCat} />}
      </div>

      {/* Footer CTA */}
      <div style={{ marginTop: 12 }}>
        {step === 1 && (
          <button className="tap" onClick={() => go(2)} disabled={!interactive} style={primaryBtn}>
            Categorize
            <MFVIcon name="chevron" size={14} stroke={2.5} />
          </button>
        )}
        {step === 2 && (
          <button className="tap" onClick={() => go(3)} disabled={!interactive} style={primaryBtn}>
            Confirm & save
            <MFVIcon name="check" size={14} stroke={2.5} />
          </button>
        )}
        {step === 3 && (
          <button className="tap" disabled={!interactive} style={{
            ...primaryBtn,
            background: 'var(--chip-bg)', color: 'var(--text)',
          }}>
            Done
          </button>
        )}
      </div>
    </div>
  );
}

const primaryBtn = {
  width: '100%', height: 50, borderRadius: 14, border: 'none',
  background: 'var(--text)', color: 'var(--bg)', cursor: 'pointer',
  fontFamily: 'var(--font-sans)', fontSize: 14, fontWeight: 600,
  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
};

// Step 1 — email arrived
function AddStep1({ pending, onNext }) {
  const m = mfvMoney(pending.amount);
  return (
    <div className="rise">
      <div style={{ fontSize: 11, color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: 0.6, fontWeight: 600 }}>New transaction</div>
      <div style={{ fontSize: 22, fontWeight: 700, letterSpacing: -0.6, marginTop: 4, marginBottom: 16 }}>
        We found one in your inbox.
      </div>

      {/* Email source card */}
      <div className="card" style={{ padding: 14, marginBottom: 14 }}>
        <div className="row" style={{ gap: 10 }}>
          <div style={{
            width: 34, height: 34, borderRadius: 11,
            background: 'var(--c-cyan-soft)', color: 'var(--c-cyan)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <MFVIcon name="mail" size={16} stroke={2} />
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 12, color: 'var(--text-2)' }}>From {pending.sender}</div>
            <div style={{ fontSize: 13, fontWeight: 600, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {pending.subject}
            </div>
          </div>
        </div>
        <div className="row" style={{ gap: 6, marginTop: 12, paddingTop: 12, borderTop: '1px solid var(--border)' }}>
          <span style={{ fontSize: 10, color: 'var(--text-2)' }}>Detected</span>
          <span className="num" style={{ fontSize: 10, color: 'var(--c-positive)', fontWeight: 600 }}>● Live</span>
          <span style={{ marginLeft: 'auto', fontSize: 10, color: 'var(--text-3)' }}>{pending.when}</span>
        </div>
      </div>

      {/* Parsed amount */}
      <div style={{ padding: '14px 0', textAlign: 'center', marginBottom: 6 }}>
        <div className="num-display" style={{ fontSize: 44, fontWeight: 600, letterSpacing: -1.6 }}>
          {m.whole}<span style={{ fontSize: 22, color: 'var(--text-2)' }}>.{m.cents}</span>
        </div>
        <div style={{ fontSize: 13, color: 'var(--text-2)', marginTop: 6 }}>
          at <b style={{ color: 'var(--text)' }}>{pending.merchant}</b>
        </div>
      </div>
    </div>
  );
}

// Step 2 — choose category
function AddStep2({ pending, chosen, onChoose }) {
  const m = mfvMoney(pending.amount);
  const cats = Object.values(MFV_CATEGORIES).filter((c) => c.id !== 'income');
  return (
    <div className="rise">
      <div style={{ fontSize: 11, color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: 0.6, fontWeight: 600 }}>Pick a bucket</div>
      <div style={{ fontSize: 22, fontWeight: 700, letterSpacing: -0.6, marginTop: 4, marginBottom: 16 }}>
        How should we classify this?
      </div>

      {/* Compact summary */}
      <div className="card" style={{ padding: '10px 12px', marginBottom: 14 }}>
        <div className="row" style={{ gap: 10 }}>
          <MFVCatGlyph catId={chosen} size={36} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 13, fontWeight: 600 }}>{pending.merchant}</div>
            <div style={{ fontSize: 11, color: 'var(--text-2)' }}>{pending.when}</div>
          </div>
          <div className="num-display" style={{ fontSize: 16, fontWeight: 600 }}>{m.whole}<span style={{ fontSize: 11, color: 'var(--text-2)' }}>.{m.cents}</span></div>
        </div>
      </div>

      {/* Suggestion */}
      <div style={{
        padding: '10px 12px', borderRadius: 14, marginBottom: 10,
        border: '1px dashed var(--c-purple)', background: 'var(--c-purple-soft)',
        display: 'flex', alignItems: 'center', gap: 10,
      }}>
        <MFVIcon name="sparkle" size={14} stroke={2.2} color="var(--c-purple)" />
        <span style={{ fontSize: 12, color: 'var(--text)' }}>
          Suggested: <b>{MFV_CATEGORIES[pending.suggested].label}</b>
        </span>
      </div>

      {/* Cat grid */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8 }}>
        {cats.map((c) => {
          const active = chosen === c.id;
          return (
            <button key={c.id} onClick={() => onChoose(c.id)} className="tap" style={{
              padding: '12px 8px', borderRadius: 14, cursor: 'pointer',
              background: active ? 'var(--surface-2)' : 'var(--surface)',
              border: '1.5px solid', borderColor: active ? c.color : 'var(--border)',
              display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6,
              fontFamily: 'var(--font-sans)', color: 'var(--text)',
            }}>
              <div style={{
                width: 28, height: 28, borderRadius: 9,
                background: c.color,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: '#0B0B0F',
              }}>
                <MFVIcon name={c.icon} size={14} stroke={2.2} />
              </div>
              <span style={{ fontSize: 10, fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '100%' }}>{c.label}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

// Step 3 — confirmation
function AddStep3({ pending, cat }) {
  const m = mfvMoney(pending.amount);
  const c = MFV_CATEGORIES[cat];
  return (
    <div className="rise" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center', paddingTop: 16 }}>
      <div style={{
        width: 72, height: 72, borderRadius: 24,
        background: 'rgba(52,224,161,0.14)', color: 'var(--c-positive)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        marginBottom: 16,
      }}>
        <MFVIcon name="check" size={34} stroke={2.5} />
      </div>
      <div style={{ fontSize: 22, fontWeight: 700, letterSpacing: -0.6, textWrap: 'balance' }}>
        Saved to {c.label}.
      </div>
      <div style={{ fontSize: 13, color: 'var(--text-2)', marginTop: 6, marginBottom: 24, textWrap: 'balance' }}>
        Your {c.label.toLowerCase()} budget is now <b className="num" style={{ color: 'var(--text)' }}>71% used</b>.
      </div>

      <div className="card" style={{ width: '100%', padding: 14, textAlign: 'left' }}>
        <div className="row" style={{ gap: 10 }}>
          <MFVCatGlyph catId={cat} size={40} radius={12} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 14, fontWeight: 600 }}>{pending.merchant}</div>
            <div style={{ fontSize: 11, color: 'var(--text-2)', marginTop: 1 }}>
              {c.label} · {pending.when}
            </div>
          </div>
          <div className="num-display" style={{ fontSize: 16, fontWeight: 600 }}>{m.whole}<span style={{ fontSize: 11, color: 'var(--text-2)' }}>.{m.cents}</span></div>
        </div>
        <div className="row" style={{ gap: 6, marginTop: 12 }}>
          <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 4,
            padding: '3px 8px', borderRadius: 999,
            background: 'var(--c-cyan-soft)', color: 'var(--c-cyan)',
            fontSize: 10, fontWeight: 600,
          }}>
            <MFVIcon name="mail" size={10} stroke={2.4} /> Email-sourced
          </span>
          <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 4,
            padding: '3px 8px', borderRadius: 999,
            background: 'var(--chip-bg)', color: 'var(--text-2)',
            fontSize: 10, fontWeight: 500,
          }}>
            Visa ····4082
          </span>
        </div>
      </div>

      <div className="row" style={{ width: '100%', marginTop: 14, gap: 8 }}>
        <button className="tap" style={{
          flex: 1, height: 40, borderRadius: 12, cursor: 'pointer',
          background: 'var(--chip-bg)', color: 'var(--text)',
          border: '1px solid var(--border)',
          fontFamily: 'var(--font-sans)', fontSize: 12, fontWeight: 500,
        }}>Flag for review</button>
        <button className="tap" style={{
          flex: 1, height: 40, borderRadius: 12, cursor: 'pointer',
          background: 'var(--chip-bg)', color: 'var(--text)',
          border: '1px solid var(--border)',
          fontFamily: 'var(--font-sans)', fontSize: 12, fontWeight: 500,
        }}>Split</button>
      </div>
    </div>
  );
}

Object.assign(window, { GoalsScreen, AddTxFlow, AddStep1, AddStep2, AddStep3 });
