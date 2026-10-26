// Mobile menu toggle. The site works without JavaScript; this only opens and closes the menu on small screens.
const toggle = document.querySelector(".nav-toggle");
const nav = document.getElementById("nav");
if (toggle && nav) {
	toggle.addEventListener("click", () => {
		const open = nav.classList.toggle("open");
		toggle.setAttribute("aria-expanded", String(open));
	});
	nav.addEventListener("click", (event) => {
		if (event.target instanceof HTMLAnchorElement) {
			nav.classList.remove("open");
			toggle.setAttribute("aria-expanded", "false");
		}
	});
}
