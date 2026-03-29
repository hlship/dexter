// DEX Frontend Code
import { attribute } from "datastar";

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

const FLIP_DURATION = 300;
const FLIP_EASING = "ease-in-out";
const ARROW_FADE_MS = 150;

// Snapshot of box positions from the previous render.
let boxSnapshot = new Map();
let isFirstRender = true;
let pendingTimeout = null;
let activeAnimations = [];

// Snapshot positions of all artifact boxes.
function snapshotBoxPositions() {
  const positions = new Map();
  document.querySelectorAll('[id^="box-"]').forEach((el) => {
    const rect = el.getBoundingClientRect();
    positions.set(el.id, { top: rect.top, left: rect.left });
  });
  return positions;
}

// Cancel any in-flight FLIP animations and pending timeouts.
function cancelPendingFlip() {
  if (pendingTimeout) {
    clearTimeout(pendingTimeout);
    pendingTimeout = null;
  }
  for (const anim of activeAnimations) {
    anim.cancel();
  }
  activeAnimations = [];
}

// FLIP animate boxes from old positions to new, fade in new boxes.
// Returns array of Animation objects for cancellation.
function animateBoxes(oldPositions) {
  const anims = [];
  document.querySelectorAll('[id^="box-"]').forEach((el) => {
    const newRect = el.getBoundingClientRect();
    const old = oldPositions.get(el.id);

    if (old) {
      const dx = old.left - newRect.left;
      const dy = old.top - newRect.top;
      if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
        anims.push(
          el.animate(
            [{ transform: `translate(${dx}px, ${dy}px)` }, { transform: "translate(0, 0)" }],
            { duration: FLIP_DURATION, easing: FLIP_EASING }
          )
        );
      }
    } else {
      anims.push(
        el.animate([{ opacity: 0, transform: "scale(0.95)" }, { opacity: 1, transform: "scale(1)" }], {
          duration: FLIP_DURATION,
          easing: FLIP_EASING,
        })
      );
    }
  });
  return anims;
}

// Datastar attribute plugin: data-draw-arrows="<connections-json>"
// Called by Datastar after each DOM morph. Orchestrates:
// 1. FLIP animation of boxes (using snapshot from previous render)
// 2. Arrow fade-out → redraw at final positions → fade-in
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

    const svg = el.querySelector("#arrow-overlay");

    if (isFirstRender) {
      // First render: just draw arrows immediately, take initial snapshot
      isFirstRender = false;
      requestAnimationFrame(() => {
        drawArrows(el, connections);
        boxSnapshot = snapshotBoxPositions();
      });
    } else {
      // Cancel any in-flight animation from a previous rapid click
      cancelPendingFlip();

      // Capture old positions, then immediately update snapshot to current
      // DOM positions so the next rapid click animates from here, not from
      // two renders ago.
      const oldPositions = boxSnapshot;
      boxSnapshot = snapshotBoxPositions();

      // Fade out arrows immediately
      if (svg) {
        svg.style.transition = `opacity ${ARROW_FADE_MS}ms ease-in-out`;
        svg.style.opacity = "0";
      }

      // FLIP animate boxes from old positions to new
      requestAnimationFrame(() => {
        activeAnimations = animateBoxes(oldPositions);

        // After boxes settle, redraw arrows at final positions and fade in
        pendingTimeout = setTimeout(() => {
          pendingTimeout = null;
          activeAnimations = [];
          drawArrows(el, connections);
          if (svg) {
            svg.style.opacity = "1";
          }
          // Update snapshot to final settled positions
          boxSnapshot = snapshotBoxPositions();
        }, FLIP_DURATION);
      });
    }

    // Redraw on window resize (no animation needed)
    const resizeObserver = new ResizeObserver(() => {
      requestAnimationFrame(() => drawArrows(el, connections));
    });
    resizeObserver.observe(el);

    return () => {
      resizeObserver.disconnect();
    };
  },
});

console.log("DEX Frontend Loaded");
