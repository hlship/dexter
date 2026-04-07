// DEX Frontend Code
import { attribute } from "./datastar.js";

const SVG_NS = "http://www.w3.org/2000/svg";

// Arrow color palette — must match server-side version-match-color values.
const ARROW_COLORS = ["#000000", "#16a34a", "#dc2626", "#ca8a04", "#64748b"];

// Creates <defs> with arrowhead markers and minimal SVG styles.
function ensureArrowDefs(svg) {
  if (svg.querySelector("#arrowhead-0")) return;

  // Minimal SVG styles — just pointer-events for arrow interactivity
  const style = document.createElementNS(SVG_NS, "style");
  style.textContent = `
    .arrow-group { pointer-events: visibleStroke; cursor: default; }
    .arrow-hit { pointer-events: stroke; }
  `;
  svg.appendChild(style);

  // One arrowhead marker per color
  const defs = document.createElementNS(SVG_NS, "defs");
  ARROW_COLORS.forEach((color, i) => {
    const marker = document.createElementNS(SVG_NS, "marker");
    marker.setAttribute("id", `arrowhead-${i}`);
    marker.setAttribute("markerWidth", "8");
    marker.setAttribute("markerHeight", "6");
    marker.setAttribute("refX", "8");
    marker.setAttribute("refY", "3");
    marker.setAttribute("orient", "auto");
    marker.setAttribute("markerUnits", "strokeWidth");

    const path = document.createElementNS(SVG_NS, "path");
    path.setAttribute("d", "M0,0 L8,3 L0,6 Z");
    path.setAttribute("fill", color);
    marker.appendChild(path);
    defs.appendChild(marker);
  });
  svg.appendChild(defs);
}

// Returns the marker URL for a given arrow color.
function markerForColor(color) {
  const idx = ARROW_COLORS.indexOf(color);
  return `url(#arrowhead-${idx >= 0 ? idx : ARROW_COLORS.length - 1})`;
}

// Creates or reuses a shared HTML tooltip for version mismatch labels.
// Positioned absolutely within the dep-viewer container.
function getOrCreateTooltip(container) {
  let tip = container.querySelector("#arrow-tooltip");
  if (!tip) {
    tip = document.createElement("div");
    tip.id = "arrow-tooltip";
    tip.className =
      "absolute pointer-events-none opacity-0 transition-opacity duration-150 " +
      "px-2 py-1 rounded-md border border-slate-300 bg-white shadow-md " +
      "text-sm font-mono font-semibold z-10 -translate-x-1/2 -translate-y-full";
    container.appendChild(tip);
  }
  return tip;
}

// Computes a cubic bezier path between two box elements.
// For cross-column: horizontal curve from source right edge to target left edge.
// For intra-column: arc that bows outward.
// For bypass: arc above or below the center node to avoid crossing through it.
function computeArrowPath(fromRect, toRect, containerRect, conn) {
  // Blend factor: control points are shifted 20% toward each other's y,
  // giving arrowheads a natural angle on steep connections.
  const blend = 0.2;

  if (conn.type === "intra-column") {
    // Determine which side to bow from
    const isLeft = fromRect.right < containerRect.left + containerRect.width / 2;
    const x = isLeft ? fromRect.left - containerRect.left : fromRect.right - containerRect.left;
    const y1 = fromRect.top + fromRect.height / 2 - containerRect.top;
    const y2 = toRect.top + toRect.height / 2 - containerRect.top;
    const bow = isLeft ? -40 : 40;
    const cx = x + bow;
    const cy1 = y1 + (y2 - y1) * blend;
    const cy2 = y2 - (y2 - y1) * blend;
    return `M${x},${y1} C${cx},${cy1} ${cx},${cy2} ${x},${y2}`;
  }

  if (conn.type === "bypass" && conn.centerId) {
    // Route around the center node: arc above or below to avoid crossing through it
    const centerEl = document.getElementById(conn.centerId);
    if (centerEl) {
      const centerRect = centerEl.getBoundingClientRect();
      const x1 = fromRect.right - containerRect.left;
      const y1 = fromRect.top + fromRect.height / 2 - containerRect.top;
      const x2 = toRect.left - containerRect.left;
      const y2 = toRect.top + toRect.height / 2 - containerRect.top;
      const midY = (y1 + y2) / 2;
      const centerY = centerRect.top + centerRect.height / 2 - containerRect.top;
      const centerTop = centerRect.top - containerRect.top;
      const centerBottom = centerRect.bottom - containerRect.top;

      // Route above if midpoint is above center, otherwise below
      const goAbove = midY <= centerY;
      const clearance = 30;
      const peakY = goAbove ? centerTop - clearance : centerBottom + clearance;

      // Two-segment cubic: source → peak above/below center → target
      const cx1 = x1 + (x2 - x1) * 0.3;
      const cx2 = x1 + (x2 - x1) * 0.7;
      return `M${x1},${y1} C${cx1},${peakY} ${cx2},${peakY} ${x2},${y2}`;
    }
  }

  // Cross-column: gentle horizontal bezier
  const x1 = fromRect.right - containerRect.left;
  const y1 = fromRect.top + fromRect.height / 2 - containerRect.top;
  const x2 = toRect.left - containerRect.left;
  const y2 = toRect.top + toRect.height / 2 - containerRect.top;
  const cx = (x1 + x2) / 2;
  const cy1 = y1 + (y2 - y1) * blend;
  const cy2 = y2 - (y2 - y1) * blend;
  return `M${x1},${y1} C${cx},${cy1} ${cx},${cy2} ${x2},${y2}`;
}

// Draws all arrows into the SVG overlay based on connection data and actual box positions.
function drawArrows(container, connections) {
  const svg = container.querySelector("#arrow-overlay");
  if (!svg) return;

  const containerRect = container.getBoundingClientRect();

  // Clear existing arrow groups (keep defs and style)
  svg.querySelectorAll(".arrow-group").forEach((g) => g.remove());

  ensureArrowDefs(svg);

  const tooltip = getOrCreateTooltip(container);

  for (const conn of connections) {
    const fromEl = document.getElementById(conn.fromId);
    const toEl = document.getElementById(conn.toId);
    if (!fromEl || !toEl) continue;

    const fromRect = fromEl.getBoundingClientRect();
    const toRect = toEl.getBoundingClientRect();

    const d = computeArrowPath(fromRect, toRect, containerRect, conn);
    const color = conn.color || "#64748b";
    const isMismatch = conn.requestedVersion !== conn.resolvedVersion;

    // Group: invisible hit area + visible arrow
    const group = document.createElementNS(SVG_NS, "g");
    group.classList.add("arrow-group");

    // Wide invisible path for easier hover targeting
    const hitArea = document.createElementNS(SVG_NS, "path");
    hitArea.classList.add("arrow-hit");
    hitArea.setAttribute("d", d);
    hitArea.setAttribute("stroke", "transparent");
    hitArea.setAttribute("stroke-width", "14");
    hitArea.setAttribute("fill", "none");
    group.appendChild(hitArea);

    // Visible arrow path
    const arrowPath = document.createElementNS(SVG_NS, "path");
    arrowPath.classList.add("arrow");
    arrowPath.setAttribute("d", d);
    arrowPath.setAttribute("stroke", color);
    arrowPath.setAttribute("stroke-width", "2");
    arrowPath.setAttribute("fill", "none");
    arrowPath.setAttribute("marker-end", markerForColor(color));
    group.appendChild(arrowPath);

    svg.appendChild(group);

    // Hover: thicken + brighten arrow, show version tooltip for mismatches
    group.addEventListener("mouseenter", () => {
      arrowPath.setAttribute("stroke-width", "4");
      arrowPath.style.filter = "brightness(1.4)";

      if (isMismatch) {
        const totalLen = arrowPath.getTotalLength();
        const mid = arrowPath.getPointAtLength(totalLen / 2);

        tooltip.textContent = conn.requestedVersion;
        tooltip.style.color = color;
        tooltip.style.borderColor = color;
        tooltip.style.left = `${mid.x}px`;
        tooltip.style.top = `${mid.y - 6}px`;
        tooltip.classList.remove("opacity-0");
        tooltip.classList.add("opacity-100");
      }
    });

    group.addEventListener("mouseleave", () => {
      arrowPath.setAttribute("stroke-width", "2");
      arrowPath.style.filter = "";

      tooltip.classList.remove("opacity-100");
      tooltip.classList.add("opacity-0");
    });
  }

}

// --- FLIP Animation for artifact boxes ---
// The draw-arrows plugin's apply() is called by Datastar after each DOM morph,
// making it the reliable signal that layout has changed. No MutationObserver needed.

const FLIP_DURATION = 500;
const FLIP_EASING = "ease-in-out";
const ARROW_FADE_MS = 100;

// Snapshot of box positions from the previous render.
let boxSnapshot = new Map();
let isFirstRender = true;
let sequenceAbort = null;   // AbortController for the current animation sequence
let activeAnimations = [];  // Web Animation objects for FLIP moves

// Deferred arrow state — arrows are only drawn when no animation is active.
// Updated each time draw-arrows apply runs; the ResizeObserver and
// afterBoxesSettle always use these to get the latest data.
let arrowContainer = null;
let arrowConnections = null;
let arrowsDirty = false;     // true if arrows need redrawing after animation

function drawArrowsIfIdle() {
  if (sequenceAbort) {
    // Animation in progress — mark dirty so afterBoxesSettle redraws.
    arrowsDirty = true;
    return;
  }
  if (arrowContainer && arrowConnections) {
    drawArrows(arrowContainer, arrowConnections);
    arrowsDirty = false;
  }
}

// Snapshot positions of all artifact boxes.
function snapshotBoxPositions() {
  const positions = new Map();
  document.querySelectorAll('[id^="box-"]').forEach((el) => {
    const rect = el.getBoundingClientRect();
    positions.set(el.id, { top: rect.top, left: rect.left });
  });
  return positions;
}

// Cancel any in-flight animation sequence.
// Aborts the AbortController (which auto-removes transitionend listeners),
// cancels any active Web Animations, and clears inline FLIP styles so
// boxes snap to their current DOM positions cleanly.
function cancelPendingFlip() {
  if (sequenceAbort) {
    sequenceAbort.abort();
    sequenceAbort = null;
  }
  for (const anim of activeAnimations) {
    anim.cancel();
  }
  activeAnimations = [];
  // Clear any lingering inline transform/opacity from applyFlipOffsets
  document.querySelectorAll('[id^="box-"]').forEach((el) => {
    el.style.transform = "";
    el.style.opacity = "";
  });
}

// Compute FLIP offsets between old and new box positions.
// Returns a Map of element id → { el, dx, dy } for moved boxes, or
// { el, isNew: true } for boxes that weren't in the previous snapshot.
function computeFlipOffsets(oldPositions) {
  const offsets = new Map();
  document.querySelectorAll('[id^="box-"]').forEach((el) => {
    const newRect = el.getBoundingClientRect();
    const old = oldPositions.get(el.id);
    if (old) {
      const dx = old.left - newRect.left;
      const dy = old.top - newRect.top;
      if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
        offsets.set(el.id, { el, dx, dy });
      }
    } else {
      offsets.set(el.id, { el, isNew: true });
    }
  });
  return offsets;
}

// Apply FLIP offsets as inline styles so boxes visually stay at their
// old positions. This must run synchronously before the browser paints
// to prevent a flash of the final layout.
function applyFlipOffsets(offsets) {
  for (const [, entry] of offsets) {
    if (entry.isNew) {
      entry.el.style.opacity = "0";
    } else {
      entry.el.style.transform = `translate(${entry.dx}px, ${entry.dy}px)`;
    }
  }
}

// Animate boxes from their FLIP offsets to their final positions.
// Returns array of Animation objects.
function animateBoxes(offsets) {
  const anims = [];
  for (const [, entry] of offsets) {
    if (entry.isNew) {
      // Clear the inline hide so the animation can control opacity
      entry.el.style.opacity = "";
      anims.push(
        entry.el.animate(
          [{ opacity: 0, transform: "scale(0.95)" }, { opacity: 1, transform: "scale(1)" }],
          { duration: FLIP_DURATION, easing: FLIP_EASING }
        )
      );
    } else {
      // Animation takes precedence over the inline transform while active.
      // Clear inline style so it doesn't interfere after animation finishes.
      entry.el.style.transform = "";
      anims.push(
        entry.el.animate(
          [{ transform: `translate(${entry.dx}px, ${entry.dy}px)` }, { transform: "translate(0, 0)" }],
          { duration: FLIP_DURATION, easing: FLIP_EASING }
        )
      );
    }
  }
  return anims;
}

// Datastar attribute plugin: data-draw-arrows="<connections-json>"
// Called by Datastar after each DOM morph. Orchestrates:
// 1. Arrow fade-out (waits for transitionend)
// 2. FLIP animation of boxes (waits for all Animation.finished promises)
// 3. Arrow redraw at final positions → fade-in
attribute({
  name: "draw-arrows",
  keyReq: "denied",
  valReq: "must",
  apply: ({ el, value }) => {
    let connections;
    try {
      connections = JSON.parse(value);
    } catch (e) {
      console.warn("draw-arrows: invalid JSON:", e);
      return () => {};
    }

    // Update deferred arrow state with latest connections data.
    // The SVG has data-ignore-morph so its children (arrow paths) survive
    // the morph — old arrows can be faded out without redrawing.
    arrowContainer = el;
    arrowConnections = connections;

    const svg = el.querySelector("#arrow-overlay");

    if (isFirstRender) {
      // First render: just draw arrows immediately, take initial snapshot
      isFirstRender = false;
      requestAnimationFrame(() => {
        drawArrowsIfIdle();
        boxSnapshot = snapshotBoxPositions();
      });
    } else {
      // Cancel any in-flight animation from a previous rapid click
      cancelPendingFlip();

      // Capture old positions, then snapshot current (new) positions.
      // snapshotBoxPositions() forces a reflow so getBoundingClientRect
      // returns the post-morph layout.
      const oldPositions = boxSnapshot;
      boxSnapshot = snapshotBoxPositions();

      // Compute FLIP offsets and apply them as inline styles immediately
      // (before the browser paints) so boxes visually stay at their old
      // positions. This prevents a flash of the final layout.
      const flipOffsets = computeFlipOffsets(oldPositions);
      applyFlipOffsets(flipOffsets);

      // Create an AbortController for this sequence. Its signal is passed
      // to addEventListener (auto-removes listeners on abort) and checked
      // between async steps to bail out if a new click has cancelled us.
      const abort = new AbortController();
      const { signal } = abort;
      sequenceAbort = abort;

      // --- Step 1: Fade out old arrows ---
      if (svg) {
        svg.style.transition = `opacity ${ARROW_FADE_MS}ms ease-in-out`;
        svg.style.opacity = "0";
      }

      // Wait for the CSS transition to finish, then run steps 2–3.
      // If svg is absent or already invisible, skip straight to boxes.
      const needsFade = svg && getComputedStyle(svg).opacity !== "0";

      const afterFadeOut = () => {
        if (signal.aborted) return;

        // --- Step 2: FLIP animate boxes from old to new positions ---
        activeAnimations = animateBoxes(flipOffsets);

        if (activeAnimations.length === 0) {
          afterBoxesSettle();
          return;
        }

        Promise.all(activeAnimations.map((a) => a.finished))
          .then(afterBoxesSettle)
          .catch(() => {}); // animations were cancelled — ignore
      };

      const afterBoxesSettle = () => {
        if (signal.aborted) return;
        sequenceAbort = null;
        activeAnimations = [];

        // --- Step 3: Draw arrows at final positions with latest data ---
        drawArrowsIfIdle();
        if (svg) {
          svg.style.opacity = "1";
        }
        boxSnapshot = snapshotBoxPositions();
      };

      if (needsFade) {
        svg.addEventListener("transitionend", afterFadeOut, { once: true, signal });
      } else {
        requestAnimationFrame(afterFadeOut);
      }
    }

    // Redraw on window resize (no animation needed).
    // drawArrowsIfIdle is a no-op during animation sequences, and
    // afterBoxesSettle will redraw with latest data when the sequence ends.
    const resizeObserver = new ResizeObserver(() => {
      requestAnimationFrame(drawArrowsIfIdle);
    });
    resizeObserver.observe(el);

    return () => {
      resizeObserver.disconnect();
    };
  },
});

/**
 * Datastar plugin: data-track-height
 *
 * Observes the element's height via ResizeObserver and computes the maximum
 * number of artifact boxes that fit. When the count changes, sets el.value
 * and dispatches a 'change' event so that a data-on:change handler can
 * send the new max-visible to the server.
 *
 * Dynamically measures a .dep-column element (left or right side column)
 * to read the actual rendered box height, gap, and overflow-indicator
 * overhead. Overflow indicators are always present in the DOM (rendered
 * with transparent text when inactive) so the layout is stable.
 *
 * Fallback constants are used only before any columns have been rendered.
 */
const FALLBACK_BOX_SLOT_HEIGHT = 68; // box + gap fallback
const FALLBACK_COLUMN_PADDING = 40;  // vertical padding/margin fallback

/**
 * Measures column layout metrics from a rendered .dep-column element.
 *
 * Looks for a side column (which always has two overflow-indicator children
 * plus zero or more box children), measures a box's height, the column gap,
 * and the total space consumed by the indicators.
 *
 * Returns { boxHeight, gap, overhead } or null if no measurable column exists.
 */
function measureColumnMetrics(container) {
  // Find a box inside a .dep-column, then navigate to its parent column.
  // This avoids picking an empty column (e.g. the left column at ROOT
  // which has no dependants — only two empty overflow indicators).
  const box = container.querySelector(".dep-column [id^='box-']");
  if (!box) return null;

  const column = box.closest(".dep-column");
  if (!column) return null;

  const boxHeight = box.getBoundingClientRect().height;
  if (boxHeight <= 0) return null;

  const gap = parseFloat(getComputedStyle(column).rowGap) || 0;

  // Measure non-box children (the always-present overflow indicators).
  // Each indicator's height + one gap between it and the adjacent box.
  let overhead = 0;
  for (const child of column.children) {
    if (!child.id || !child.id.startsWith("box-")) {
      overhead += child.getBoundingClientRect().height + gap;
    }
  }

  return { boxHeight, gap, overhead };
}

attribute({
  name: "track-height",
  keyReq: "denied",
  valReq: "must",
  apply: ({ el }) => {
    let lastMaxVisible = -1;

    function onResize() {
      // Use the observed container's clientHeight as the available height.
      // This is always accurate because the ResizeObserver tracks this
      // element, and its height is set by flex-1 + min-h-0 — a definite
      // size. We do NOT use the column's clientHeight because Firefox
      // doesn't always resolve h-full to a definite value inside a
      // flex-row container whose height comes from flex-1.
      const height = el.clientHeight;
      const metrics = measureColumnMetrics(el);

      let mv;
      if (metrics) {
        // N boxes with gap between them need:
        //   N * boxHeight + (N - 1) * gap  =  N * (boxHeight + gap) - gap
        // Available space for boxes = height - overhead.
        // Solving: N ≤ (available + gap) / (boxHeight + gap)
        const available = height - metrics.overhead;
        const slot = metrics.boxHeight + metrics.gap;
        mv = Math.max(1, Math.floor((available + metrics.gap) / slot));
      } else {
        mv = Math.max(1, Math.floor((height - FALLBACK_COLUMN_PADDING) / FALLBACK_BOX_SLOT_HEIGHT));
      }

      if (mv !== lastMaxVisible) {
        lastMaxVisible = mv;
        el.value = String(mv);
        el.dispatchEvent(new Event("change", { bubbles: true }));
      }
    }

    // Initial computation — double-rAF ensures the morphed DOM has been
    // fully laid out in all browsers. Firefox sometimes needs the extra
    // frame before flex children have their final computed sizes.
    requestAnimationFrame(() => requestAnimationFrame(onResize));

    // ResizeObserver fires after layout, so measurements are already
    // accurate — no need to defer with requestAnimationFrame.
    const observer = new ResizeObserver(onResize);
    observer.observe(el);

    return () => observer.disconnect();
  },
});

/**
 * Datastar plugin: data-accel
 *
 * Declares a keyboard accelerator (Cmd on Mac, Ctrl on Windows/Linux) that
 * triggers a click on the element. The key character comes from the attribute
 * key, and the __shift modifier requires Shift to be held.
 *
 * Suppressed when a modal is open or the element has the disabled attribute.
 *
 * Requires the classes 'tooltip' and the attribute
 * 'data-preserve-attr' as 'data-tip' (because client-side modification
 * would get overwritten). 
 *
 * Usage in Hiccup:
 *   :data-accel "s"           ;; Cmd/Ctrl+S triggers el.click() or el.focus()
 *   :data-accel "z"           ;; Cmd/Ctrl+Z
 *   :data-accel__shift "z"    ;; Cmd/Ctrl+Shift+Z
 */
const isMac = navigator.platform.startsWith('Mac') || navigator.userAgent.includes('Mac');
const modSymbol = isMac ? '⌘' : 'Ctrl+';

attribute({
  name: 'accel',
  requirement: { key: 'denied', value: 'must' },
  apply({ el, value, mods }) {
    const accelKey = value;
    const needsShift = mods.has('shift');
    
    // Set DaisyUI tooltip showing the shortcut, e.g. "⌘S" or "Ctrl+Shift+Z"
    const label = modSymbol + (needsShift ? (isMac ? '⇧' : 'Shift+') : '') + accelKey.toUpperCase();
    el.setAttribute('data-tip', label);
   
    const handler = (e) => {
      if (!(e.metaKey || e.ctrlKey)) return;
      if (e.key !== accelKey) return;
      if (!!e.shiftKey !== needsShift) return;
      if (el.hasAttribute('disabled')) return;
      if (document.querySelector('#modal-container > *')) return;
      e.preventDefault();
       
      if (el.type === "text") 
        { el.focus(); }
      else 
        { el.click(); }
    };

    window.addEventListener('keydown', handler, true);
    return () => window.removeEventListener('keydown', handler, true);
  }
});

// --- Server Disconnect Detection ---
// Datastar dispatches "datastar-fetch" custom events on document with
// detail.type indicating the lifecycle stage. When the server stops,
// Datastar retries the SSE connection with exponential backoff. We show
// the disconnect modal on the first retry attempt rather than waiting
// for all 10 retries to exhaust (~3 minutes).
//
// The modal markup lives in the Hiccup template (views.clj) inside
// #modal-container. The data-accel plugin already checks for
// '#modal-container > *' to suppress keyboard shortcuts when a modal
// is visible.

document.addEventListener("datastar-fetch", (e) => {
  const { type } = e.detail;

  if (type === "retrying" || type === "retries-failed") {
    document.getElementById("disconnect-modal")?.classList.add("modal-open");
  }
});

console.log("DEX Frontend Loaded");
