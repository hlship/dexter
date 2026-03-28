// DEX Frontend Code
import { attribute } from "datastar";

const SVG_NS = "http://www.w3.org/2000/svg";

// Creates the <defs> element with arrowhead marker, if not already present.
function ensureArrowDefs(svg) {
  if (svg.querySelector("#arrowhead")) return;

  const defs = document.createElementNS(SVG_NS, "defs");
  const marker = document.createElementNS(SVG_NS, "marker");
  marker.setAttribute("id", "arrowhead");
  marker.setAttribute("markerWidth", "8");
  marker.setAttribute("markerHeight", "6");
  marker.setAttribute("refX", "8");
  marker.setAttribute("refY", "3");
  marker.setAttribute("orient", "auto");
  marker.setAttribute("markerUnits", "strokeWidth");

  const path = document.createElementNS(SVG_NS, "path");
  path.setAttribute("d", "M0,0 L8,3 L0,6 Z");
  path.setAttribute("fill", "#64748b");
  marker.appendChild(path);
  defs.appendChild(marker);
  svg.appendChild(defs);
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

  // Clear existing paths (keep defs)
  svg.querySelectorAll("path.arrow").forEach((p) => p.remove());

  ensureArrowDefs(svg);

  for (const conn of connections) {
    const fromEl = document.getElementById(conn.fromId);
    const toEl = document.getElementById(conn.toId);
    if (!fromEl || !toEl) continue;

    const fromRect = fromEl.getBoundingClientRect();
    const toRect = toEl.getBoundingClientRect();

    const d = computeArrowPath(fromRect, toRect, containerRect, conn);

    const path = document.createElementNS(SVG_NS, "path");
    path.classList.add("arrow");
    path.setAttribute("d", d);
    path.setAttribute("stroke", "#64748b");
    path.setAttribute("stroke-width", "2");
    path.setAttribute("fill", "none");
    path.setAttribute("marker-end", "url(#arrowhead)");
    svg.appendChild(path);
  }
}

// Datastar attribute plugin: data-draw-arrows="<connections-json>"
// Reads connection JSON, measures box positions, draws SVG arrows.
// Redraws on resize.
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

    // Initial draw (deferred to let layout settle)
    requestAnimationFrame(() => drawArrows(el, connections));

    // Redraw on resize
    const resizeObserver = new ResizeObserver(() => {
      requestAnimationFrame(() => drawArrows(el, connections));
    });
    resizeObserver.observe(el);

    // Cleanup
    return () => {
      resizeObserver.disconnect();
    };
  },
});

console.log("DEX Frontend Loaded");
