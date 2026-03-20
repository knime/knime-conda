function escapeHtml(text) {
  var div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

function updateView() {
  var platform = document.getElementById('platform').value;
  var packages = allPackages[platform] || [];

  // Build lookup from package name to locked version for this platform
  var lockLookup = {};
  for (var i = 0; i < packages.length; i++) {
    lockLookup[packages[i].name] = packages[i];
  }

  // Requested packages section
  var reqHtml = '<h4>Requested Packages</h4>';
  var reqNames = Object.keys(requestedDeps);
  if (reqNames.length === 0) {
    reqHtml += '<p class="no-data">No dependencies specified in pixi.toml</p>';
  } else {
    reqHtml += '<table><thead><tr><th>Package</th><th>Requested</th><th>Locked Version</th><th>Source</th></tr></thead><tbody>';
    for (var i = 0; i < reqNames.length; i++) {
      var name = reqNames[i];
      var requested = requestedDeps[name];
      var locked = lockLookup[name];
      var lockedVersion = locked ? escapeHtml(locked.version) : '<span class="no-data">not found</span>';
      var source = locked ? '<span class="source-badge source-' + locked.source + '">' + locked.source + '</span>' : '-';
      reqHtml += '<tr><td>' + escapeHtml(name) + '</td><td>' + escapeHtml(requested) + '</td><td>' + lockedVersion + '</td><td>' + source + '</td></tr>';
    }
    reqHtml += '</tbody></table>';
  }
  document.getElementById('requested-section').innerHTML = reqHtml;

  // All packages section
  var allHtml = '<details>';
  allHtml += '<summary>All Installed Packages (' + packages.length + ')</summary>';
  if (packages.length === 0) {
    allHtml += '<p class="no-data">No packages found for this platform</p>';
  } else {
    allHtml += '<table><thead><tr><th>Package</th><th>Version</th><th>Source</th></tr></thead><tbody>';
    for (var i = 0; i < packages.length; i++) {
      var pkg = packages[i];
      allHtml += '<tr><td>' + escapeHtml(pkg.name) + '</td><td>' + escapeHtml(pkg.version) + '</td>';
      allHtml += '<td><span class="source-badge source-' + pkg.source + '">' + pkg.source + '</span></td></tr>';
    }
    allHtml += '</tbody></table>';
  }
  allHtml += '</details>';
  document.getElementById('all-section').innerHTML = allHtml;
}

// Initial render
updateView();
