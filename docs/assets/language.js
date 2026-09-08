(() => {
  const switcher = document.querySelector('.language-switcher');
  if (!switcher) return;

  const key = 'porter.language';
  const ui = JSON.parse(document.getElementById('language-ui').textContent);
  const main = document.getElementById('content');
  const description = document.querySelector('meta[name="description"]');
  const english = document.createElement('template');
  english.innerHTML = main.innerHTML;
  const translations = new Map([['en', {
    content: english.content, title: document.title, description: description.content
  }]]);
  document.querySelectorAll('template[data-translation]').forEach(template => {
    translations.set(template.dataset.translation, {
      content: template.content, title: template.dataset.title, description: template.dataset.description
    });
  });

  let choice;
  try {
    choice = localStorage.getItem(key) || localStorage.getItem('porter.setupLanguage');
  } catch (_) { /* Storage is optional. */ }
  if (!translations.has(choice)) choice = 'auto';

  const supportedLanguage = value => {
    const parts = value.toLowerCase().split('-');
    // Traditional Chinese should fall through to the next browser preference.
    if (parts[0] === 'zh') {
      if (parts.includes('hant') || (!parts.includes('hans') && parts.some(part => ['tw', 'hk', 'mo'].includes(part)))) return null;
      return translations.has('zh-Hans') ? 'zh-Hans' : null;
    }
    const lang = parts[0] === 'pt' ? 'pt-BR' : parts[0];
    return translations.has(lang) ? lang : null;
  };
  const browserLanguage = () => (navigator.languages || [navigator.language])
    .map(supportedLanguage).find(Boolean) || 'en';
  let active = 'en';
  const applyLanguage = () => {
    const lang = choice === 'auto' ? browserLanguage() : choice;
    const text = ui[lang];
    document.documentElement.dir = text.dir || 'ltr';
    if (lang !== active) {
      const translation = translations.get(lang);
      main.replaceChildren(translation.content.cloneNode(true));
      document.documentElement.lang = lang;
      document.title = translation.title;
      description.content = translation.description;
      active = lang;
      if (location.hash) {
        let id = location.hash.slice(1);
        try { id = decodeURIComponent(id); } catch (_) { /* Keep malformed fragments literal. */ }
        document.getElementById(id)?.scrollIntoView();
      }
    }
    document.querySelectorAll('[data-i18n]').forEach(element => {
      element.textContent = text[element.dataset.i18n];
    });
    document.querySelectorAll('[data-i18n-label]').forEach(element => {
      element.setAttribute('aria-label', text[element.dataset.i18nLabel]);
    });
    document.getElementById('language-flag').textContent = text.flag;
    document.getElementById('language-name').textContent = text.name;
    switcher.querySelector('summary').setAttribute('aria-label', `${text.language}: ${text.name}`);
    switcher.querySelectorAll('[data-language]').forEach(button => {
      button.setAttribute('aria-pressed', String(button.dataset.language === choice));
    });
  };

  switcher.querySelectorAll('[data-language]').forEach(button => {
    button.addEventListener('click', () => {
      choice = button.dataset.language;
      try {
        localStorage.setItem(key, choice);
        localStorage.removeItem('porter.setupLanguage');
      } catch (_) { /* Switching still works for this page. */ }
      applyLanguage();
      switcher.open = false;
      switcher.querySelector('summary').focus({preventScroll: true});
    });
  });
  document.addEventListener('click', event => {
    if (!switcher.contains(event.target)) switcher.open = false;
  });
  switcher.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      switcher.open = false;
      switcher.querySelector('summary').focus();
    }
  });
  window.addEventListener('languagechange', () => {
    if (choice === 'auto') applyLanguage();
  });
  applyLanguage();
  switcher.hidden = false;
})();
