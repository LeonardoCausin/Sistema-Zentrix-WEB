(function () {
  "use strict";

  window.ZentrixPageConfig = Object.freeze({
    apiCacheMaxAge: 15 * 60 * 1000,
    viewCacheMaxAge: 10 * 60 * 1000,
    viewCachePrefix: "zentrix-view-cache:",
    viewStatePrefix: "zentrix-view-state:",
    clientCacheVersion: "20260708-backup-monitor",
    prefetchPeriods: Object.freeze(["today", "7d", "month", "year"])
  });
})();
