"""Reviewed repository aliases; installation and distribution IDs never change.

Copied with the publishers into each repository. CI supplies a complete GitHub
repository, never a basename or a URL. Accept only this explicit organization
and alias table before using credentials or choosing a distribution directory.
"""
OWNER = 'NimbyRails-France'
REPOSITORIES = {
    'sdk': 'sdk',
    'hub': 'hub',
    'tco': 'tco',
    'signalisationfrancaiserealiste': 'ab-signalisation-lumineuse',
    'signal-placement': 'ba-signal-placement',
    'time-change': 'bb-timechange',
    'bc-train-super-long': 'bc-train-super-long',
}
PROJECTS = frozenset(REPOSITORIES)
_PROJECT_BY_REPOSITORY = {
    OWNER + '/' + name: project
    for project, current in REPOSITORIES.items()
    for name in (project, current)
}


def project_id(repository):
    """Resolve an exact, approved old/current repository to its stable ID."""
    if not isinstance(repository, str) or repository not in _PROJECT_BY_REPOSITORY:
        raise ValueError('Unsupported repository')
    return _PROJECT_BY_REPOSITORY[repository]


def historical_asset_url(repository, tag, name):
    """Old Hub versions expect the historical project slug in catalogues."""
    return f'https://github.com/{OWNER}/{project_id(repository)}/releases/download/{tag}/{name}'


def matches_asset_url(repository, tag, name, url):
    """Accept an exact asset URL for this project, before or after its rename."""
    project = project_id(repository)
    return url in {
        f'https://github.com/{OWNER}/{slug}/releases/download/{tag}/{name}'
        for slug in (project, REPOSITORIES[project])
    }
