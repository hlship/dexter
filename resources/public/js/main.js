// DEX Frontend Code

// Size reporting for #dep-viewer.
// Uses ResizeObserver to measure the element and POST {width, height}
// to the action URL found in its data-report-size attribute.
// Debounced to at most every 50ms.
function initSizeWatch() {
  const el = document.getElementById("dep-viewer");
  if (!el) {
    // Element not yet in DOM (SSE hasn't rendered yet), retry
    requestAnimationFrame(initSizeWatch);
    return;
  }

  const attrValue = el.getAttribute("data-report-size");
  if (!attrValue) {
    console.debug("sizewatch: no data-report-size attribute on #dep-viewer");
    return;
  }

  // Extract action URL from the Hyper action expression
  const match = attrValue.match(/['"]([^'"]*\/hyper\/actions[^'"]*)['"]/);
  if (!match) {
    console.warn("sizewatch: could not extract action URL from:", attrValue);
    return;
  }
  const actionUrl = match[1];
  console.debug("sizewatch: observing #dep-viewer, actionUrl:", actionUrl);

  let lastWidth = 0;
  let lastHeight = 0;
  let timer = null;

  function reportSize(w, h) {
    fetch(actionUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ formData: { width: String(w), height: String(h) } }),
    });
  }

  const observer = new ResizeObserver((entries) => {
    for (const entry of entries) {
      const { width, height } = entry.contentRect;
      const w = Math.round(width);
      const h = Math.round(height);
      if (Math.abs(w - lastWidth) > 1 || Math.abs(h - lastHeight) > 1) {
        lastWidth = w;
        lastHeight = h;
        if (timer) clearTimeout(timer);
        timer = setTimeout(() => {
          timer = null;
          console.debug("sizewatch:", w, "x", h);
          reportSize(w, h);
        }, 50);
      }
    }
  });

  observer.observe(el);
}

initSizeWatch();

console.log("DEX Frontend Loaded");
