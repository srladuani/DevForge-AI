/** Leaves this application for another address (an identity provider's sign-in page). A function of its own so tests can observe it. */
export function goTo(url: string): void {
  window.location.assign(url)
}
