// Keeps the controllers' progress applying plugin changes current while the page is open.
(function () {
  function refresh() {
    document.querySelectorAll(".cnj-cluster-plugins").forEach(function (notice) {
      fetch(notice.dataset.statusUrl, { headers: { "X-Requested-With": "XMLHttpRequest" } })
        .then(function (rsp) {
          return rsp.ok ? rsp.text() : null;
        })
        .then(function (html) {
          if (html !== null) {
            notice.querySelector(".cnj-cluster-plugins__status").innerHTML = html;
          }
        })
        .catch(function () {});
    });
  }
  window.setInterval(refresh, 5000);
})();
