// Custom JavaScript for Kafka Connect OpenTelemetry Documentation

document.addEventListener('DOMContentLoaded', function() {

  // Add copy button feedback
  const copyButtons = document.querySelectorAll('.md-clipboard');
  copyButtons.forEach(button => {
    button.addEventListener('click', function() {
      const icon = this.querySelector('svg');
      const originalColor = icon.style.color;

      // Visual feedback
      icon.style.color = '#10b981';
      setTimeout(() => {
        icon.style.color = originalColor;
      }, 1000);
    });
  });

  // Add external link icons
  const externalLinks = document.querySelectorAll('a[href^="http"]');
  externalLinks.forEach(link => {
    if (!link.hostname.includes(window.location.hostname)) {
      link.setAttribute('target', '_blank');
      link.setAttribute('rel', 'noopener noreferrer');

      // Add external link icon
      if (!link.querySelector('.external-icon')) {
        const icon = document.createElement('span');
        icon.className = 'external-icon';
        icon.innerHTML = ' ↗';
        icon.style.fontSize = '0.8em';
        icon.style.verticalAlign = 'super';
        link.appendChild(icon);
      }
    }
  });

  // Smooth scroll for anchor links
  document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function(e) {
      const target = document.querySelector(this.getAttribute('href'));
      if (target) {
        e.preventDefault();
        target.scrollIntoView({
          behavior: 'smooth',
          block: 'start'
        });

        // Update URL without jumping
        if (history.pushState) {
          history.pushState(null, null, this.getAttribute('href'));
        }
      }
    });
  });

  // Add version badge to navigation
  const nav = document.querySelector('.md-header__title');
  if (nav) {
    const versionBadge = document.createElement('span');
    versionBadge.className = 'md-version';
    versionBadge.textContent = 'v1.0.0';
    versionBadge.style.marginLeft = '0.5rem';
    versionBadge.style.fontSize = '0.75rem';
    versionBadge.style.opacity = '0.7';
    nav.appendChild(versionBadge);
  }

  // Enhance code blocks with language labels
  document.querySelectorAll('pre code').forEach(block => {
    const language = block.className.match(/language-(\w+)/);
    if (language && language[1]) {
      const label = document.createElement('div');
      label.className = 'code-label';
      label.textContent = language[1].toUpperCase();
      label.style.position = 'absolute';
      label.style.top = '0.5rem';
      label.style.right = '3rem';
      label.style.fontSize = '0.75rem';
      label.style.color = 'var(--md-default-fg-color--light)';
      label.style.opacity = '0.6';

      const pre = block.parentElement;
      pre.style.position = 'relative';
      pre.insertBefore(label, block);
    }
  });

  // Add reading time estimate
  const content = document.querySelector('.md-content__inner');
  if (content) {
    const text = content.textContent;
    const words = text.trim().split(/\s+/).length;
    const readingTime = Math.ceil(words / 200); // Average reading speed: 200 words/min

    const timeEstimate = document.createElement('div');
    timeEstimate.className = 'reading-time';
    timeEstimate.innerHTML = `<em>📖 ${readingTime} min read</em>`;
    timeEstimate.style.color = 'var(--md-default-fg-color--light)';
    timeEstimate.style.fontSize = '0.875rem';
    timeEstimate.style.marginBottom = '1rem';

    const firstHeading = content.querySelector('h1');
    if (firstHeading && firstHeading.nextElementSibling) {
      firstHeading.parentNode.insertBefore(timeEstimate, firstHeading.nextElementSibling);
    }
  }

  // Keyboard shortcuts
  document.addEventListener('keydown', function(e) {
    // Alt + S: Focus search
    if (e.altKey && e.key === 's') {
      e.preventDefault();
      const searchInput = document.querySelector('.md-search__input');
      if (searchInput) {
        searchInput.focus();
      }
    }

    // Alt + H: Go to home
    if (e.altKey && e.key === 'h') {
      e.preventDefault();
      window.location.href = '/';
    }
  });

  // Table of contents highlighting
  const observerOptions = {
    root: null,
    rootMargin: '0px',
    threshold: 0.5
  };

  const headingObserver = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      const id = entry.target.getAttribute('id');
      const tocLink = document.querySelector(`.md-nav__link[href="#${id}"]`);

      if (entry.isIntersecting && tocLink) {
        // Remove active class from all TOC links
        document.querySelectorAll('.md-nav__link--active').forEach(link => {
          link.classList.remove('md-nav__link--active');
        });

        // Add active class to current section
        tocLink.classList.add('md-nav__link--active');
      }
    });
  }, observerOptions);

  // Observe all headings
  document.querySelectorAll('h2[id], h3[id]').forEach(heading => {
    headingObserver.observe(heading);
  });

  // Add "Back to top" button
  const backToTop = document.createElement('button');
  backToTop.className = 'back-to-top';
  backToTop.innerHTML = '↑';
  backToTop.style.cssText = `
    position: fixed;
    bottom: 2rem;
    right: 2rem;
    width: 3rem;
    height: 3rem;
    border-radius: 50%;
    background-color: var(--md-primary-fg-color);
    color: white;
    border: none;
    cursor: pointer;
    opacity: 0;
    transition: opacity 0.3s, transform 0.2s;
    z-index: 1000;
    font-size: 1.5rem;
    display: flex;
    align-items: center;
    justify-content: center;
  `;

  document.body.appendChild(backToTop);

  // Show/hide back to top button
  window.addEventListener('scroll', function() {
    if (window.scrollY > 300) {
      backToTop.style.opacity = '0.7';
    } else {
      backToTop.style.opacity = '0';
    }
  });

  backToTop.addEventListener('mouseenter', function() {
    this.style.opacity = '1';
    this.style.transform = 'scale(1.1)';
  });

  backToTop.addEventListener('mouseleave', function() {
    this.style.opacity = '0.7';
    this.style.transform = 'scale(1)';
  });

  backToTop.addEventListener('click', function() {
    window.scrollTo({
      top: 0,
      behavior: 'smooth'
    });
  });

  // Console Easter egg
  console.log('%c🎉 Kafka Connect OpenTelemetry Connector', 'color: #ef4444; font-size: 20px; font-weight: bold;');
  console.log('%cBuilt with MkDocs Material', 'color: #3b82f6; font-size: 14px;');
  console.log('%cContribute: https://github.com/conduktor/kafka-connect-opentelemetry', 'color: #10b981; font-size: 12px;');
});
