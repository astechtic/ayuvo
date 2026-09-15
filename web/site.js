// Ayuvo site — progressive enhancement only. The page is fully usable without it.
(function () {
  var nav = document.getElementById('nav');
  if (nav) {
    var onScroll = function () {
      if (window.scrollY > 8) nav.classList.add('scrolled');
      else nav.classList.remove('scrolled');
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
  }

  // Close the mobile menu after a link is chosen.
  var navToggle = document.getElementById('nav-toggle');
  Array.prototype.forEach.call(document.querySelectorAll('.nav-links a'), function (link) {
    link.addEventListener('click', function () { if (navToggle) navToggle.checked = false; });
  });

  // Scroll reveals — content stays visible if IntersectionObserver is absent.
  if ('IntersectionObserver' in window && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) { e.target.classList.add('in-view'); io.unobserve(e.target); }
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.06 });
    Array.prototype.forEach.call(
      document.querySelectorAll('.chapter, .step, .faq-item, .section-head, .cta-content, .platforms li'),
      function (el) { el.classList.add('reveal'); io.observe(el); }
    );
  }
})();
