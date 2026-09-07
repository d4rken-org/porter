(() => {
  const key = "porter.setupLanguage";
  let preferred;
  try { preferred = localStorage.getItem(key); } catch (_) { /* Storage is optional. */ }

  const switcher = document.querySelector(".language-switcher");
  if (switcher) {
    document.addEventListener("click", event => {
      if (!switcher.contains(event.target)) switcher.open = false;
    });
    switcher.addEventListener("keydown", event => {
      if (event.key === "Escape") {
        switcher.open = false;
        switcher.querySelector("summary").focus();
      }
    });
  }

  const languageLinks = document.querySelectorAll("[data-setup-language]");
  const syncSection = () => languageLinks.forEach(link => { link.hash = window.location.hash; });
  // Both translations share section IDs so switching preserves a deep link.
  syncSection();
  window.addEventListener("hashchange", syncSection);
  languageLinks.forEach(link => {
    link.addEventListener("click", () => {
      try { localStorage.setItem(key, link.dataset.setupLanguage); } catch (_) { /* Links still work. */ }
    });
  });

  const suggestion = document.getElementById("setup-language-suggestion");
  const browserLanguage = (navigator.languages || [navigator.language])
    .map(value => value.toLowerCase().split("-")[0])
    .find(value => value === "en" || value === "de");
  if (suggestion && (preferred === "de" || (!preferred && browserLanguage === "de"))) {
    suggestion.hidden = false;
  }

  // An explicit setup-page URL always wins; preferences only affect navigation from other pages.
  if (!document.querySelector(".language-switcher") && preferred === "de") {
    document.querySelectorAll("[data-setup-link]").forEach(link => {
      link.href = link.dataset.deUrl;
      link.textContent = "Installieren und starten";
      link.hreflang = "de";
    });
  }
})();
