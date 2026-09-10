/**
 * Opens the side panel for the current window.
 *
 * Must be invoked from a click handler on an extension page (popup/options).
 * Messaging the service worker first loses the user gesture and Chrome rejects
 * sidePanel.open().
 *
 * @returns {Promise<void>}
 */
export function openSidePanelFromClick() {
  return chrome.windows.getCurrent().then((win) => {
    if (win?.id == null) {
      throw new Error('NO_WINDOW');
    }
    return chrome.sidePanel.open({ windowId: win.id });
  });
}
