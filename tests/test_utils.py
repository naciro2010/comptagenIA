import pytest
from datetime import datetime
from src.utils import parse_date, parse_amount

@pytest.mark.parametrize("test_input,expected", [
    ("01/01/2023", datetime(2023, 1, 1)),
    ("2023-01-01", datetime(2023, 1, 1)),
    ("1 Jan 2023", datetime(2023, 1, 1)),
    ("Not a date", None),
])
def test_parse_date(test_input, expected):
    assert parse_date(test_input) == expected

@pytest.mark.parametrize("test_input,expected", [
    ("1,234.56", 1234.56),
    ("1.234,56", 1234.56),
    ("1 234,56", 1234.56),
    ("100", 100.0),
    ("-50.25", -50.25),
    ("Not a number", None),
])
def test_parse_amount(test_input, expected):
    assert parse_amount(test_input) == expected
